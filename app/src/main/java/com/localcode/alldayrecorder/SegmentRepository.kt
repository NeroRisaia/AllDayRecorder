package com.localcode.alldayrecorder

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Segment(val name: String, val sizeBytes: Long, val modifiedAt: Long)

object SegmentRepository {

    fun outputDir(context: Context): File {
        return context.getExternalFilesDir(null) ?: File(context.filesDir, "recordings")
    }

    fun nextFile(dir: File, index: Int): File {
        val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).format(Date())
        return File(dir, String.format(Locale.US, "seg_%04d_%s.m4a", index, stamp))
    }

    fun segments(context: Context): List<Segment> {
        val files = outputDir(context).listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.name.endsWith(".m4a") }.sortedBy { it.name }.map {
            Segment(it.name, it.length(), it.lastModified())
        }
    }
}
