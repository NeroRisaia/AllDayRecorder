package com.localcode.alldayrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class RecorderService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var segmentCount = 0
    private var retryDelayMs = 1_000L

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        startForegroundCompat()
        segmentCount = SegmentRepository.segments(this).size
        showNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (recorder == null && !startSegment()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopRecorder()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSegment(): Boolean {
        val dir = SegmentRepository.outputDir(this)
        if (!dir.exists() && !dir.mkdirs()) return false

        val file = SegmentRepository.nextFile(dir, segmentCount + 1)
        val newRecorder = buildRecorder()
        newRecorder.setOutputFile(file.absolutePath)

        newRecorder.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                handler.post { roll() }
            }
        }
        newRecorder.setOnErrorListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN) {
                handler.post {
                    stopRecorder()
                    file.delete()
                    scheduleRetry()
                }
            }
            return@setOnErrorListener
        }

        return try {
            newRecorder.prepare()
            newRecorder.start()
            recorder = newRecorder
            activeFile = file
            segmentCount += 1
            retryDelayMs = 1_000L
            showNotification()
            true
        } catch (e: Exception) {
            safeRelease(newRecorder)
            file.delete()
            scheduleRetry()
            false
        }
    }

    private fun roll() {
        stopRecorder()
        startSegment()
    }

    private fun scheduleRetry() {
        handler.postDelayed({ startSegment() }, retryDelayMs)
        retryDelayMs = minOf(retryDelayMs * 2, 30_000L)
    }

    private fun buildRecorder(): MediaRecorder {
        val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            MediaRecorder()
        }
        newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        newRecorder.setAudioEncodingBitRate(AppConfig.AUDIO_BITRATE)
        newRecorder.setAudioSamplingRate(AppConfig.AUDIO_SAMPLE_RATE)
        newRecorder.setMaxDuration(AppConfig.SEGMENT_SECONDS * 1000)
        return newRecorder
    }

    private fun stopRecorder() {
        val active = recorder ?: return
        recorder = null
        try {
            active.stop()
        } catch (e: Exception) {
            // keep the partial segment as it is
        }
        safeRelease(active)
        activeFile = null
    }

    private fun safeRelease(target: MediaRecorder) {
        try {
            target.release()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(AppConfig.CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    AppConfig.CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                AppConfig.NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(AppConfig.NOTIFICATION_ID, buildNotification())
        }
    }

    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(AppConfig.NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, AppConfig.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_segments, segmentCount))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        /** 録音中（未確定）のファイル。停止処理が終わると null。 */
        @Volatile
        var activeFile: java.io.File? = null
            private set

        /** サービスが生きている間 true。onDestroy で録音を確定させた後に false。 */
        @Volatile
        var running: Boolean = false
            private set
    }
}
