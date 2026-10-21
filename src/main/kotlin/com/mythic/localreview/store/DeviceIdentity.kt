package com.mythic.localreview.store

import com.mythic.localreview.format.Ulid
import com.mythic.localreview.format.Values
import com.mythic.localreview.format.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The id of this device (SPEC 5.1): one per machine, kept in [dir] (the store's `.state` folder) and shared by every IDE on the machine that runs
 * the plugin. It is never synced by the plugin, but [dir] may sit in a folder that a file-sync service copies to another machine, so the id is
 * stored with a [machine] fingerprint, and a different fingerprint means the id was copied: a new one is generated.
 */
class DeviceIdentity(private val dir: Path, private val machine: String) {
    @Volatile private var cached: String? = null

    /** The id of this device, generated on first use. Safe to call from several threads, and from several IDEs at once. */
    fun id(): String {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: resolve().also { cached = it }
        }
    }

    private fun resolve(): String {
        Files.createDirectories(dir)
        val inProcess = processLocks.computeIfAbsent(dir.toAbsolutePath().normalize()) { java.util.concurrent.locks.ReentrantLock() }
        inProcess.lock()
        try {
            // Several IDEs may start at once on a new machine: the first to take the lock writes the id, the others read it.
            FileChannel.open(dir.resolve("device.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
                ch.lock().use {
                    read()?.let { return it }
                    val id = Ulid.next()
                    write(id)
                    return id
                }
            }
        } finally {
            inProcess.unlock()
        }
    }

    /** The stored id, if it exists, is valid, and was written on this machine. */
    private fun read(): String? {
        val file = dir.resolve(FILE)
        if (!Files.isRegularFile(file)) return null
        val o = try {
            Json.parseToJsonElement(Files.readString(file)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val id = o["id"].string()?.takeIf { Values.isId(it) } ?: return null
        return id.takeIf { o["machine"].string() == machine }
    }

    private fun write(id: String) {
        val tmp = dir.resolve("$FILE.tmp")
        val text = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("id", id)
            put("machine", machine)
        })
        FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { ch ->
            ch.write(java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)))
            ch.force(true)
        }
        Files.move(tmp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val FILE = "device.json"

        /** Java refuses a second OS lock on one file in one process, so users of one folder in this process queue here first. */
        val processLocks = java.util.concurrent.ConcurrentHashMap<Path, java.util.concurrent.locks.ReentrantLock>()
    }
}

/** An identifier of this machine that differs between machines, for noticing a copied device id (SPEC 5.1). Best effort; falls back to the host name. */
object MachineFingerprint {
    val current: String by lazy { detect() }

    private fun detect(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val id = try {
            when {
                "win" in os -> windowsMachineGuid()
                "mac" in os || "darwin" in os -> macPlatformUuid()
                else -> listOf("/etc/machine-id", "/var/lib/dbus/machine-id").firstNotNullOfOrNull { p ->
                    runCatching { Files.readString(Path.of(p)).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
                }
            }
        } catch (_: Exception) {
            null
        }
        return id?.let { "id:$it" } ?: "host:" + (runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: "unknown")
    }

    private fun run(vararg cmd: String): String? {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        return if (p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) out else null
    }

    private fun windowsMachineGuid(): String? =
        run("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid")?.let { Regex("MachineGuid\\s+REG_SZ\\s+(\\S+)").find(it)?.groupValues?.get(1) }

    private fun macPlatformUuid(): String? =
        run("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")?.let { Regex("\"IOPlatformUUID\"\\s*=\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
}
