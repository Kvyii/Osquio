package com.kvi.osquio.ui.rankings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

@Composable
fun RankingsScreen(vm: RankingsViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.load() }

    Column(modifier = Modifier.fillMaxSize().padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Text("Rankings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            is RankingsUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            is RankingsUiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
            }
            is RankingsUiState.Loaded -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = s.isThisMonth, onClick = { vm.setFilter(true) }, label = { Text("This Month") })
                    FilterChip(selected = !s.isThisMonth, onClick = { vm.setFilter(false) }, label = { Text("All Time") })
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(s.badges) { badge -> BadgeCard(badge) }
                }
            }
        }
    }
}

@Composable
private fun BadgeCard(badge: Badge) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(badge.iconRes),
                contentDescription = badge.name,
                modifier = Modifier.size(48.dp),
                tint = androidx.compose.ui.graphics.Color.Unspecified,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(badge.name, style = MaterialTheme.typography.titleMedium)
                if (badge.holder != null) {
                    Text(badge.holder.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(badge.detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(badge.detail, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            badge.holder?.avatarUrl?.let { url ->
                AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(48.dp))
            }
        }
    }
}
