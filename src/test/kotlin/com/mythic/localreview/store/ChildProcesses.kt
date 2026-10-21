package com.mythic.localreview.store

import com.mythic.localreview.format.Event
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Entry points run in separate JVMs by the tests, to prove the device lock works between processes (two IDEs on one machine),
 * which no amount of threads in one JVM can show.
 */
object AppendMain {
    /** args: eventsDir device count worker */
    @JvmStatic
    fun main(args: Array<String>) {
        val writer = EventLogWriter(lockWaitMillis = 60_000)
        repeat(args[2].toInt()) { i -> writer.append(Path.of(args[0]), args[1], Event.ReviewRenamed("worker ${args[3]} event $i")) }
        println("DONE")
    }
}

object LockHolderMain {
    /** args: lockFile. Takes the lock, prints LOCKED, and holds it until its standard input is closed. */
    @JvmStatic
    fun main(args: Array<String>) {
        FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            ch.lock().use {
                println("LOCKED")
                System.out.flush()
                while (System.`in`.read() >= 0) Unit
            }
        }
    }
}

/** Starts `java` with the test classpath, via an argument file so a long classpath cannot exceed the command line limit. */
fun startJava(mainClass: String, workDir: Path, vararg args: String): Process {
    val java = ProcessHandle.current().info().command().orElseThrow()
    val argFile = Files.createTempFile(workDir, "java-", ".args")
    Files.writeString(argFile, "-cp\n\"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"\n")
    return ProcessBuilder(listOf(java, "@$argFile", mainClass) + args).redirectErrorStream(true).start()
}
