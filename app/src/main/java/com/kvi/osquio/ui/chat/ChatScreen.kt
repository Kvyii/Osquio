package com.kvi.osquio.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatScreen(onNavigateToSettings: () -> Unit = {}) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Chat", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Chat — coming soon", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
