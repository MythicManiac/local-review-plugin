package com.mythic.localreview.store

import com.mythic.localreview.format.Values
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeviceIdentityTest {
    private val dir: Path = Files.createTempDirectory("lrw-device")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `the id is generated once and the same on the next start of the same machine`() {
        val first = DeviceIdentity(dir, "machine-A").id()
        assertTrue(Values.isId(first))
        assertEquals(first, DeviceIdentity(dir, "machine-A").id())
    }

    @Test
    fun `an id found on a different machine was copied, so a new one is made`() {
        val onA = DeviceIdentity(dir, "machine-A").id()
        val onB = DeviceIdentity(dir, "machine-B").id()
        assertNotEquals(onA, onB)
        // The copy now belongs to B; if the folder travels back, A makes yet another one rather than reuse B's.
        assertEquals(onB, DeviceIdentity(dir, "machine-B").id())
        assertNotEquals(onA, DeviceIdentity(dir, "machine-A").id())
    }

    @Test
    fun `a damaged or foreign file is replaced, not trusted`() {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("device.json"), "{ not json")
        val id = DeviceIdentity(dir, "m").id()
        assertTrue(Values.isId(id))
        Files.writeString(dir.resolve("device.json"), """{"id":"has spaces","machine":"m"}""")
        assertTrue(Values.isId(DeviceIdentity(dir, "m").id()))
    }

    @Test
    fun `IDEs starting at once on a new machine agree on one id`() {
        val start = CountDownLatch(1)
        val ids = ConcurrentHashMap.newKeySet<String>()
        val threads = (1..8).map {
            Thread {
                start.await()
                ids += DeviceIdentity(dir, "m").id() // one instance per IDE
            }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, ids.size, ids.toString())
    }
}
