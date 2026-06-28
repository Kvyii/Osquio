package com.kvi.osquio

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kvi.osquio.data.RsvpRepository
import com.kvi.osquio.data.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RsvpActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val summonId = intent.getStringExtra("summon_id") ?: return
        val response = intent.getStringExtra("response") ?: return

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                val user = UserRepository.currentUser()
                RsvpRepository.upsertRsvp(summonId, user.id, response, null)
            } catch (_: Exception) {}
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(1001)
    }
}
