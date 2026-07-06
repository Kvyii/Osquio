package com.kvi.osquio.ui.beacon

import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kvi.osquio.R
import com.kvi.osquio.data.RsvpRepository
import com.kvi.osquio.data.UserRepository
import com.kvi.osquio.notifications.RsvpSoundPlayer
import com.kvi.osquio.ui.theme.OsquioTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BeaconAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val summonId = intent.getStringExtra("summon_id")
        val title = intent.getStringExtra("title") ?: "Dota Beacon"
        val body = intent.getStringExtra("body") ?: ""

        setContent {
            OsquioTheme {
                BeaconAlertScreen(
                    title = title,
                    body = body,
                    onYes = {
                        if (summonId != null) submitRsvp(summonId, "yes")
                        finish()
                    },
                    onNo = {
                        if (summonId != null) submitRsvp(summonId, "no")
                        finish()
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    private fun submitRsvp(summonId: String, response: String) {
        RsvpSoundPlayer.playForResponse(applicationContext, response)
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val user = UserRepository.currentUser()
                RsvpRepository.upsertRsvp(summonId, user.id, response, null)
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(1001)
            } catch (_: Exception) {}
        }
    }
}

@Composable
private fun BeaconAlertScreen(
    title: String,
    body: String,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_dota),
                contentDescription = null,
                modifier = Modifier.size(100.dp),
                tint = Color(0xFFC0392B),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF9E9E9E),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(48.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onNo,
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF9E9E9E)),
                ) {
                    Text("No", style = MaterialTheme.typography.titleMedium)
                }
                Button(
                    onClick = onYes,
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC0392B)),
                ) {
                    Text("Yes", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = Color(0xFF616161))
            }
        }
    }
}
