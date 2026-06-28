package com.kvi.osquio.ui.stats

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

@Composable
fun StatsScreen(vm: StatsViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.load() }

    Column(modifier = Modifier.fillMaxSize().padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Text("Stats", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            is StatsUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            is StatsUiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
            }
            is StatsUiState.Loaded -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = s.isThisMonth,
                        onClick = { vm.setFilter(true) },
                        label = { Text("This Month") },
                    )
                    FilterChip(
                        selected = !s.isThisMonth,
                        onClick = { vm.setFilter(false) },
                        label = { Text("All Time") },
                    )
                }
                Spacer(Modifier.height(16.dp))
                val avatarSize = 56.dp
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Spacer(Modifier.width(avatarSize + 10.dp))
                    listOf("Sent", "Yes", "No", "Ignored").forEach { label ->
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                HorizontalDivider()
                LazyColumn {
                    items(s.stats) { stat ->
                        StatsRow(stat, avatarSize)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsRow(stat: UserStats, avatarSize: androidx.compose.ui.unit.Dp) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tall rounded-square avatar spanning both name + stats rows
        AsyncImage(
            model = stat.user.avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(avatarSize)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(10.dp))
        // Right side: name on top, numbers on bottom
        Column(modifier = Modifier.weight(1f)) {
            if (stat.isDeceased) {
                Text(
                    buildAnnotatedString {
                        append(stat.user.displayName)
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)) {
                            append(" (deceased)")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Text(
                    stat.user.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf(stat.summonsSent, stat.accepted, stat.rejected, stat.ignored).forEach { value ->
                    Text(
                        value.toString(),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

