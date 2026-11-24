package com.localcode.alldayrecorder

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var segmentList: ListView
    private lateinit var summary: TextView
    private lateinit var exportStatus: TextView
    private lateinit var exportAllButton: Button
    private lateinit var sendTodayButton: Button

    private var shownSegments: List<Segment> = emptyList()
    private val worker = Executors.newSingleThreadExecutor()
    private var pendingExportAll = false
    private val uiHandler = Handler(Looper.getMainLooper())

    /** 録音中は一覧を定期更新（新セグメントと「録音中」印を反映）。 */
    private val ticker = object : Runnable {
        override fun run() {
            if (RecorderService.running) refresh()
            uiHandler.postDelayed(this, LIST_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startButton = findViewById<Button>(R.id.start_button)
        val stopButton = findViewById<Button>(R.id.stop_button)
        exportAllButton = findViewById(R.id.export_all_button)
        sendTodayButton = findViewById(R.id.send_today_button)
        segmentList = findViewById(R.id.segment_list)
        summary = findViewById(R.id.summary)
        exportStatus = findViewById(R.id.export_status)

        startButton.setOnClickListener {
            if (!hasRequiredPermissions()) {
                requestRequiredPermissions()
                return@setOnClickListener
            }
            val intent = Intent(this, RecorderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            prefs().edit().remove(PREF_EXPORTED_DAY).apply()
            sendTodayButton.visibility = View.GONE
            exportStatus.text = ""
            refresh()
            // Android 9 以下: 停止後の自動書き出しに要る権限を、録音は止めずにここで求める
            if (!hasLegacyStoragePermission()) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
            }
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, RecorderService::class.java))
            exportTodayAfterStop()
        }

        exportAllButton.setOnClickListener { exportAll() }

        sendTodayButton.setOnClickListener {
            // 停止時に書き出した日の分を送る（停止後に日付が変わっても対象日がずれない）
            val day = prefs().getString(PREF_EXPORTED_DAY, null) ?: SegmentRepository.today()
            val today = SegmentRepository.completedSegmentsOf(this, day)
            if (today.isEmpty()) {
                toast(getString(R.string.no_segments_today))
            } else {
                share(today, getString(R.string.send_today))
            }
        }

        segmentList.setOnItemClickListener { _, _, position, _ -> shareOne(position) }
        segmentList.setOnItemLongClickListener { _, _, position, _ ->
            shareOne(position)
            true
        }

        askToIgnoreBatteryOptimizations()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        updateSendTodayButton()
        uiHandler.removeCallbacks(ticker)
        uiHandler.postDelayed(ticker, 1_000L)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        val segments = SegmentRepository.segments(this)
        shownSegments = segments
        val active = RecorderService.activeFile?.absolutePath
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val rows = segments.map { segment ->
            "%s  %.1f MB  %s%s".format(
                segment.name,
                segment.sizeBytes / 1_000_000.0,
                timeFormat.format(Date(segment.modifiedAt)),
                if (segment.file.absolutePath == active) "  " + getString(R.string.recording_mark) else ""
            )
        }
        segmentList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        summary.text = getString(
            R.string.summary,
            segments.size,
            segments.sumOf { it.sizeBytes } / 1_000_000.0,
            AppConfig.ESTIMATED_DAILY_BYTES / 1_000_000.0
        )
    }

    private fun shareOne(position: Int) {
        val segment = shownSegments.getOrNull(position) ?: return
        if (segment.file.absolutePath == RecorderService.activeFile?.absolutePath) {
            toast(getString(R.string.cannot_share_recording))
            return
        }
        if (!segment.file.exists()) {
            refresh()
            return
        }
        share(listOf(segment), segment.name)
    }

    private fun share(segments: List<Segment>, title: String) {
        try {
            startActivity(SegmentExporter.shareIntent(this, segments, title))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_share_target))
        }
    }

    private fun exportAll() {
        if (!hasLegacyStoragePermission()) {
            pendingExportAll = true
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
            return
        }
        exportAllButton.isEnabled = false
        exportStatus.text = getString(R.string.exporting)
        worker.execute {
            val result = SegmentExporter.export(this, SegmentRepository.completedSegments(this))
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                exportAllButton.isEnabled = true
                exportStatus.text = describe(result)
                refresh()
            }
        }
    }

    /** 停止後、録音の確定（RecorderService.onDestroy）を待ってから今日の分を書き出す。 */
    private fun exportTodayAfterStop() {
        exportStatus.text = getString(R.string.exporting)
        sendTodayButton.visibility = View.GONE
        val canWrite = hasLegacyStoragePermission()
        worker.execute {
            val deadline = SystemClock.elapsedRealtime() + STOP_WAIT_MS
            while (RecorderService.running && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(50)
            }
            val day = SegmentRepository.today()
            val todays = SegmentRepository.completedSegmentsOf(this, day)
            val result = if (canWrite) SegmentExporter.export(this, todays) else null
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                refresh()
                if (result == null) {
                    exportStatus.text = getString(R.string.need_storage_permission)
                } else {
                    exportStatus.text = getString(R.string.today_prefix) + describe(result)
                    if (result.failed.isEmpty()) {
                        prefs().edit().putString(PREF_EXPORTED_DAY, day).apply()
                    }
                }
                updateSendTodayButton(forceIfAny = true)
            }
        }
    }

    private fun updateSendTodayButton(forceIfAny: Boolean = false) {
        val exportedDay = prefs().getString(PREF_EXPORTED_DAY, null)
        val day = if (forceIfAny || exportedDay == null) SegmentRepository.today() else exportedDay
        val stopped = !RecorderService.running
        val exported = exportedDay == day
        val hasToday = SegmentRepository.completedSegmentsOf(this, day).isNotEmpty()
        sendTodayButton.visibility =
            if (stopped && hasToday && (exported || forceIfAny)) View.VISIBLE else View.GONE
    }

    private fun describe(result: ExportResult): String {
        val base = getString(R.string.export_result, result.copied, result.skipped, result.failed.size)
        return if (result.failed.isEmpty()) base else base + "\n" + result.failed.joinToString("\n")
    }

    private fun prefs() = getSharedPreferences("export", MODE_PRIVATE)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun hasLegacyStoragePermission(): Boolean =
        !SegmentExporter.needsLegacyPermission() ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun hasRequiredPermissions(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return true
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (SegmentExporter.needsLegacyPermission()) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        requestPermissions(permissions.toTypedArray(), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            refresh()
        }
        if (requestCode == REQUEST_STORAGE && pendingExportAll) {
            pendingExportAll = false
            if (hasLegacyStoragePermission()) exportAll() else toast(getString(R.string.need_storage_permission))
        }
    }

    private fun askToIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    companion object {
        private const val REQUEST_CODE = 1
        private const val REQUEST_STORAGE = 2
        private const val STOP_WAIT_MS = 10_000L
        private const val LIST_REFRESH_MS = 3_000L
        private const val PREF_EXPORTED_DAY = "exported_day"
    }
}
