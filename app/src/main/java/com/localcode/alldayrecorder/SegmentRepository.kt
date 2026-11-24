package com.localcode.alldayrecorder

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Segment(val name: String, val sizeBytes: Long, val modifiedAt: Long, val file: File) {
    /** ファイル名の日時から取った日付 (yyyyMMdd)。読めない名前は更新日時で代用。 */
    val day: String
        get() = SegmentRepository.dayOf(name)
            ?: SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(modifiedAt))
}

object SegmentRepository {

    // 例: seg_0001_20261124T093000.m4a
    private val NAME_PATTERN = Regex("""^seg_\d+_(\d{8})T\d{6}\.m4a$""")

    fun outputDir(context: Context): File {
        return context.getExternalFilesDir(null) ?: File(context.filesDir, "recordings")
    }

    fun nextFile(dir: File, index: Int): File {
        val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).format(Date())
        return File(dir, String.format(Locale.US, "seg_%04d_%s.m4a", index, stamp))
    }

    fun dayOf(name: String): String? = NAME_PATTERN.find(name)?.groupValues?.get(1)

    fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun segments(context: Context): List<Segment> {
        val files = outputDir(context).listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.name.endsWith(".m4a") }.sortedBy { it.name }.map {
            Segment(it.name, it.length(), it.lastModified(), it)
        }
    }

    /** 録音中（未確定）のファイルを除いたセグメント。 */
    fun completedSegments(context: Context): List<Segment> {
        val active = RecorderService.activeFile
        return segments(context).filter { active == null || it.file.absolutePath != active.absolutePath }
    }

    fun completedSegmentsOf(context: Context, day: String): List<Segment> =
        completedSegments(context).filter { it.day == day }
}
