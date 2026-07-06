package com.kvi.osquio.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.kvi.osquio.R

object RsvpSoundPlayer {
    fun playForResponse(context: Context, response: String) {
        val soundResId = if (response == "yes") R.raw.sound_rsvp_yes else R.raw.sound_rsvp_fail
        val player = MediaPlayer()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        val afd = context.resources.openRawResourceFd(soundResId) ?: return
        afd.use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
        player.setOnCompletionListener { it.release() }
        player.setOnErrorListener { mp, _, _ -> mp.release(); true }
        player.prepare()
        player.start()
    }
}
