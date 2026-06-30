package com.kvi.osquio.ui.rankings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage


@Composable
fun RankingsScreen(onNavigateToSettings: () -> Unit = {}, vm: RankingsViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.load() }

    Column(modifier = Modifier.fillMaxSize().padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Rankings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
        }
        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            is RankingsUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            is RankingsUiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
            }
            is RankingsUiState.Loaded -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = s.isThisMonth, onClick = { vm.setFilter(true) }, label = { Text("Last 30 Days") })
                    FilterChip(selected = !s.isThisMonth, onClick = { vm.setFilter(false) }, label = { Text("All Time") })
                }
                Spacer(Modifier.height(12.dp))
                val listState = rememberLazyListState()
                var expandedIndex by remember { mutableStateOf<Int?>(null) }
                LaunchedEffect(expandedIndex) {
                    expandedIndex?.let { index ->
                        kotlinx.coroutines.delay(150)
                        listState.animateScrollToItem(index)
                    }
                }
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(s.badges) { index, badge ->
                        BadgeCard(
                            badge = badge,
                            expanded = expandedIndex == index,
                            onToggle = { expandedIndex = if (expandedIndex == index) null else index },
                        )
                    }
                    item {
                        Text(
                            "Tap a card to expand podium",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgeCard(badge: Badge, expanded: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onToggle() },
    ) {
        Column {
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

            AnimatedVisibility(visible = expanded) {
                if (badge.podium.isEmpty()) {
                    Text(
                        "No data yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    )
                } else {
                    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
                        badge.podium.forEach { entry ->
                            PodiumRow(entry)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PodiumRow(entry: RankedEntry) {
    val medal = when (entry.rank) {
        1 -> "🥇"
        2 -> "🥈"
        3 -> "🥉"
        else -> "${entry.rank}."
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(medal, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(36.dp))
        entry.user.avatarUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.user.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(entry.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
