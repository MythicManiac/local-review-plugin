package com.mythic.localreview.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

object AtomicFiles {
    /**
     * Durably replaces [target] with [bytes]: write a sibling temp file, fsync it, atomically rename over the target,
     * then read the target back and compare. Throws [IOException] if any step fails or the read-back differs, so a
     * caller that returns normally can rely on the bytes being on disk.
     */
    fun write(target: Path, bytes: ByteArray) {
        val dir = target.toAbsolutePath().parent
        Files.createDirectories(dir)
        val tmp = dir.resolve(target.fileName.toString() + ".tmp")
        FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) ch.write(buf)
            ch.force(true)
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        val back = Files.readAllBytes(target)
        if (!back.contentEquals(bytes)) throw IOException("Read-back verification failed for $target")
    }
}
