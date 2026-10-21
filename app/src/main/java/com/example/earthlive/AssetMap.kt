package com.example.earthlive

import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Maps an uncompressed asset straight from the APK (little-endian, read-only): no copy on the Java heap, and the pages
 * the GPU upload reads are shared with the file cache. Assets must be listed in noCompress (app/build.gradle.kts).
 */
object AssetMap {
    fun map(context: Context, name: String): ByteBuffer =
        context.assets.openFd(name).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length).order(ByteOrder.LITTLE_ENDIAN)
            }
        }
}
