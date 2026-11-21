package com.localcode.alldayrecorder

object AppConfig {
    const val SEGMENT_SECONDS = 1800
    const val AUDIO_BITRATE = 32_000
    const val AUDIO_SAMPLE_RATE = 16_000

    const val CHANNEL_ID = "allday_recorder"
    const val NOTIFICATION_ID = 1001

    // 32kbps * 86400s / 8 = 345,600,000 bytes = 345.6 MB per 24h（表示は 10進 MB に統一）
    val ESTIMATED_DAILY_BYTES: Long = AUDIO_BITRATE.toLong() * 86_400L / 8L
}
