package com.kvi.osquio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kvi.osquio.data.ChatRepository
import com.kvi.osquio.data.RsvpRepository
import com.kvi.osquio.data.UserRepository
import com.kvi.osquio.ui.beacon.BeaconAlertActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MyFirebaseService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["type"]) {
            "mention" -> {
                val isForegrounded = ProcessLifecycleOwner.get().lifecycle.currentState
                    .isAtLeast(Lifecycle.State.STARTED)
                if (isForegrounded) {
                    ChatRepository.signalRefresh()
                } else {
                    val senderName = message.data["sender_name"] ?: "Someone"
                    showMentionNotification("@mention", "Message from $senderName")
                }
            }
            else -> {
                val summonId = message.data["summon_id"]
                val gameTime = message.data["game_time"]
                val summonerName = message.data["summoner_name"]
                val title = if (gameTime != null && summonerName != null)
                    "Dota Beacon"
                else
                    message.notification?.title ?: "Dota Beacon"
                val body = if (gameTime != null && summonerName != null)
                    "Game at $gameTime · Beaconed by $summonerName"
                else
                    message.notification?.body ?: "Someone is calling for a game. You in?"

                if (summonId != null) {
                    serviceScope.launch {
                        try {
                            val user = UserRepository.currentUser()
                            val rsvps = RsvpRepository.rsvpsForSummon(summonId)
                            val alreadyResponded = rsvps.any { it.userId == user.id }
                            if (!alreadyResponded) {
                                showNotification(title, body, summonId)
                            }
                        } catch (_: Exception) {
                            showNotification(title, body, summonId)
                        }
                    }
                } else {
                    showNotification(title, body, null)
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        serviceScope.launch {
            try {
                val user = UserRepository.currentUser()
                UserRepository.updateFcmToken(user.id, token)
            } catch (_: Exception) {}
        }
    }

    private fun showNotification(title: String, body: String, summonId: String?) {
        val alertChannelId = "beacon_alert_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val soundUri = Uri.parse("android.resource://${packageName}/${R.raw.beacon_sound}")
        val audioAttr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val vibration = longArrayOf(0, 500, 200, 500, 200, 500, 200, 1000)

        val alertChannel = NotificationChannel(alertChannelId, "Beacon Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(soundUri, audioAttr)
            enableVibration(true)
            vibrationPattern = vibration
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(alertChannel)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, alertChannelId)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_dota)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openPending)
            .setAutoCancel(true)

        if (summonId != null) {
            val fullScreenIntent = Intent(this, BeaconAlertActivity::class.java).apply {
                putExtra("summon_id", summonId)
                putExtra("title", title)
                putExtra("body", body)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
            }
            val fullScreenPending = PendingIntent.getActivity(
                this, 3, fullScreenIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setFullScreenIntent(fullScreenPending, true)

            val yesIntent = Intent(this, RsvpActionReceiver::class.java).apply {
                putExtra("summon_id", summonId)
                putExtra("response", "yes")
            }
            val yesPending = PendingIntent.getBroadcast(
                this, 1, yesIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val noIntent = Intent(this, RsvpActionReceiver::class.java).apply {
                putExtra("summon_id", summonId)
                putExtra("response", "no")
            }
            val noPending = PendingIntent.getBroadcast(
                this, 2, noIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, "Yes", yesPending)
            builder.addAction(0, "No", noPending)
        }

        manager.notify(1001, builder.build())
    }

    private fun showMentionNotification(title: String, body: String) {
        val channelId = "mention_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "Mentions", NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 10, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_dota)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .build()

        manager.notify(1002, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
