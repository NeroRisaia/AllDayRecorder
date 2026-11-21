package com.localcode.alldayrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var segmentList: ListView
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startButton = findViewById<Button>(R.id.start_button)
        val stopButton = findViewById<Button>(R.id.stop_button)
        segmentList = findViewById(R.id.segment_list)
        summary = findViewById(R.id.summary)

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
            refresh()
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, RecorderService::class.java))
            refresh()
        }

        askToIgnoreBatteryOptimizations()
        refresh()
    }

    private fun refresh() {
        val segments = SegmentRepository.segments(this)
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val rows = segments.map { segment ->
            "%s  %.1f MB  %s".format(
                segment.name,
                segment.sizeBytes / 1_000_000.0,
                timeFormat.format(Date(segment.modifiedAt))
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
    }
}
