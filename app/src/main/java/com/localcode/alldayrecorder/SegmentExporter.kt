package com.localcode.alldayrecorder

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

data class ExportResult(val copied: Int, val skipped: Int, val failed: List<String>)

/**
 * 端末の公開フォルダ Music/AllDayRecorder へのコピーと、共有シート用 Intent の作成。
 * 元ファイル（アプリ専用フォルダ）は変更・削除しない。
 */
object SegmentExporter {

    const val FOLDER = "AllDayRecorder"
    private const val MIME = "audio/mp4"

    /** Android 9 以下は WRITE_EXTERNAL_STORAGE が要る。 */
    fun needsLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun export(context: Context, segments: List<Segment>): ExportResult {
        var copied = 0
        var skipped = 0
        val failed = mutableListOf<String>()
        for (segment in segments) {
            try {
                val didCopy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    exportMediaStore(context, segment)
                } else {
                    exportLegacy(context, segment)
                }
                if (didCopy) copied++ else skipped++
            } catch (e: Exception) {
                failed.add("${segment.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return ExportResult(copied, skipped, failed)
    }

    /** @return true=コピーした / false=同名同サイズが既にあるので飛ばした */
    private fun exportMediaStore(context: Context, segment: Segment): Boolean {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = Environment.DIRECTORY_MUSIC + "/" + FOLDER + "/"

        var existing: Uri? = null
        var existingSize = -1L
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relativePath, segment.name),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                existing = Uri.withAppendedPath(collection, cursor.getLong(0).toString())
                existingSize = cursor.getLong(1)
            }
        }
        if (existing != null && existingSize == segment.file.length()) return false

        val target = existing ?: resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, segment.name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IllegalStateException("MediaStore insert failed")

        try {
            resolver.openOutputStream(target, "wt").use { out ->
                requireNotNull(out) { "openOutputStream returned null" }
                segment.file.inputStream().use { it.copyTo(out) }
            }
            resolver.update(target, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        } catch (e: Exception) {
            if (existing == null) resolver.delete(target, null, null)
            throw e
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun exportLegacy(context: Context, segment: Segment): Boolean {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("mkdirs failed: $dir")
        val dest = File(dir, segment.name)
        if (dest.exists() && dest.length() == segment.file.length()) return false
        val tmp = File(dir, segment.name + ".part")
        segment.file.copyTo(tmp, overwrite = true)
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IllegalStateException("rename failed: $dest")
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(MIME), null)
        return true
    }

    private fun uriFor(context: Context, segment: Segment): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", segment.file)

    /** 共有シート（chooser）用の Intent。1件なら ACTION_SEND、複数なら ACTION_SEND_MULTIPLE。 */
    fun shareIntent(context: Context, segments: List<Segment>, title: String): Intent {
        require(segments.isNotEmpty())
        val uris = ArrayList(segments.map { uriFor(context, it) })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = MIME
        val clip = ClipData(ClipDescription(segments[0].name, arrayOf(MIME)), ClipData.Item(uris[0]))
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        send.clipData = clip
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
