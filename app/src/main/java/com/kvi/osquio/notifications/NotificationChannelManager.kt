package com.kvi.osquio.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri

object NotificationChannelManager {

    private const val BEACON_LOUD_GAME_READY = "beacon_alert_loud_game_ready"
    private const val BEACON_LOUD_READY_CHECK = "beacon_alert_loud_ready_check"
    private const val BEACON_DISCRETE_TEAMS = "beacon_alert_discrete_teams"
    private const val BEACON_DISCRETE_SLACK = "beacon_alert_discrete_slack"
    private const val BEACON_SILENT = "beacon_alert_silent"
    private const val MENTION_CHANNEL = "mention_channel"
    private const val MENTION_SILENT = "mention_silent"

    private val BEACON_VIBRATION = longArrayOf(0, 500, 200, 500, 200, 500, 200, 1000)

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.createNotificationChannel(
            beaconChannel(context, BEACON_LOUD_GAME_READY, LoudSound.GAME_READY.resId, vibrate = true)
        )
        manager.createNotificationChannel(
            beaconChannel(context, BEACON_LOUD_READY_CHECK, LoudSound.READY_CHECK.resId, vibrate = true)
        )
        manager.createNotificationChannel(
            beaconChannel(context, BEACON_DISCRETE_TEAMS, DiscreteSound.TEAMS.resId, vibrate = true)
        )
        manager.createNotificationChannel(
            beaconChannel(context, BEACON_DISCRETE_SLACK, DiscreteSound.SLACK.resId, vibrate = true)
        )
        manager.createNotificationChannel(
            beaconChannel(context, BEACON_SILENT, soundResId = null, vibrate = false)
        )
        manager.createNotificationChannel(
            NotificationChannel(MENTION_CHANNEL, "Mentions", NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.createNotificationChannel(
            NotificationChannel(MENTION_SILENT, "Mentions (silent)", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun beaconChannelIdFor(decision: SoundDecision, prefs: NotificationPreferences): String = when (decision) {
        is SoundDecision.Silent -> BEACON_SILENT
        is SoundDecision.Loud -> when (prefs.loudSound) {
            LoudSound.GAME_READY -> BEACON_LOUD_GAME_READY
            LoudSound.READY_CHECK -> BEACON_LOUD_READY_CHECK
        }
        is SoundDecision.Discrete -> when (prefs.discreteSound) {
            DiscreteSound.TEAMS -> BEACON_DISCRETE_TEAMS
            DiscreteSound.SLACK -> BEACON_DISCRETE_SLACK
        }
    }

    fun mentionChannelIdFor(decision: SoundDecision): String =
        if (decision is SoundDecision.Silent) MENTION_SILENT else MENTION_CHANNEL

    private fun beaconChannel(
        context: Context,
        channelId: String,
        soundResId: Int?,
        vibrate: Boolean,
    ): NotificationChannel {
        return NotificationChannel(channelId, "Beacon Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            if (soundResId != null) {
                val soundUri = Uri.parse("android.resource://${context.packageName}/$soundResId")
                val audioAttr = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(soundUri, audioAttr)
            } else {
                setSound(null, null)
            }
            enableVibration(vibrate)
            if (vibrate) vibrationPattern = BEACON_VIBRATION
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
    }
}
