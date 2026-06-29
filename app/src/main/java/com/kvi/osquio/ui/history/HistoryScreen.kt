package com.kvi.osquio.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.kvi.osquio.data.model.SummonHistory
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault())

private val colorYes = Color(0xFF2ECC40)
private val colorYesAt = Color(0xFFFF8C00)
private val colorNo = Color(0xFFFF4136)

@Composable
fun HistoryScreen(onNavigateToSettings: () -> Unit = {}, vm: HistoryViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.load() }

    when (val s = state) {
        is HistoryUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is HistoryUiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(s.message, color = MaterialTheme.colorScheme.error)
        }
        is HistoryUiState.Calendar -> when {
            s.selectedDay != null -> HistoryDayScreen(s.selectedDay, s.dayDetail, onBack = { vm.back() })
            else -> CalendarView(s.summonsPerDay, onSelectDay = { vm.selectDay(it) }, onNavigateToSettings = onNavigateToSettings)
        }
    }
}

@Composable
private fun CalendarView(summonsPerDay: Map<LocalDate, Int>, onSelectDay: (LocalDate) -> Unit, onNavigateToSettings: () -> Unit = {}) {
    var displayMonth by remember { mutableStateOf(YearMonth.now()) }
    val today = LocalDate.now()

    Column(modifier = Modifier.fillMaxSize().padding(top = 48.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
            Text("History", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            IconButton(onClick = { displayMonth = displayMonth.minusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous month")
            }
            Text(
                displayMonth.month.name.lowercase().replaceFirstChar { it.uppercase() } + " ${displayMonth.year}",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = { displayMonth = displayMonth.plusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next month")
            }
        }

        val firstDay = displayMonth.atDay(1)
        val daysInMonth = displayMonth.lengthOfMonth()
        val startOffset = (firstDay.dayOfWeek.value % 7)
        val cells = List(startOffset) { null } + (1..daysInMonth).map { firstDay.withDayOfMonth(it) }

        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val crimson = MaterialTheme.colorScheme.primary
        val maxCount = summonsPerDay.entries
            .filter { it.key.month == displayMonth.month && it.key.year == displayMonth.year }
            .maxOfOrNull { it.value } ?: 1

        val rows = cells.chunked(7)
        val shape = RoundedCornerShape(4.dp)

        Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 2.dp)) {
            rows.forEach { week ->
                Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 2.dp)) {
                    week.forEach { date ->
                        if (date == null) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 2.dp))
                        } else {
                            val count = summonsPerDay[date] ?: 0
                            val isToday = date == today
                            val intensity = if (count > 0) (count.toFloat() / maxCount) else 0f

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(horizontal = 2.dp)
                                    .then(
                                        if (isToday)
                                            Modifier.border(2.dp, crimson, shape)
                                        else
                                            Modifier.background(
                                                crimson.copy(alpha = if (count > 0) 0.15f + intensity * 0.7f else 0.05f),
                                                shape
                                            )
                                    )
                                    .clickable(enabled = count > 0) { onSelectDay(date) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        date.dayOfMonth.toString(),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (count > 0 || isToday) FontWeight.Bold else FontWeight.Normal
                                        ),
                                        color = if (isToday) crimson
                                                else if (count > 0) Color.White
                                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                    )
                                    if (count > 0) {
                                        Text(
                                            count.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isToday) crimson else Color.White.copy(alpha = 0.7f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    repeat(7 - week.size) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryDayScreen(day: LocalDate, summons: List<SummonHistory>, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 48.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(dateFmt.format(day.atStartOfDay(ZoneId.systemDefault())), style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(8.dp))
        if (summons.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("No beacons on this day.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                items(summons) { summon -> BeaconCard(summon) }
            }
        }
    }
}

@Composable
private fun BeaconCard(summon: SummonHistory) {
    val respondents = runCatching { summon.snapshot["respondents"]?.jsonArray }.getOrNull() ?: return

    val timeLabel = timeFmt.format(Instant.parse(summon.gameTime))
    val status = summon.status.replaceFirstChar { it.uppercase() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Beacon at $timeLabel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                // Summoner first
                val summonerEntry = respondents.map { it.jsonObject }
                    .firstOrNull { it["user_id"]?.jsonPrimitive?.content == summon.summonerId }
                ResponseAvatar(
                    avatarUrl = summonerEntry?.get("avatar_url")?.jsonPrimitive?.content,
                    tintColor = null,
                    borderColor = colorYes,
                    borderWidth = 2.5.dp,
                )

                Spacer(Modifier.width(4.dp))

                val responseOrder = listOf("yes", "yes_at_time", "no")
                val others = respondents
                    .map { it.jsonObject }
                    .filter { it["user_id"]?.jsonPrimitive?.content != summon.summonerId }
                    .sortedBy { r -> responseOrder.indexOf(r["response"]?.jsonPrimitive?.content).takeIf { it >= 0 } ?: Int.MAX_VALUE }

                others.forEach { r ->
                    val avatarUrl = r["avatar_url"]?.jsonPrimitive?.content
                    val response = r["response"]?.jsonPrimitive?.content ?: return@forEach
                    val tint = when (response) {
                        "yes" -> colorYes.copy(alpha = 0.55f)
                        "yes_at_time" -> colorYesAt.copy(alpha = 0.55f)
                        "no" -> colorNo.copy(alpha = 0.75f)
                        else -> return@forEach
                    }
                    val label = if (response == "yes_at_time") {
                        val responseTime = r["response_time"]?.jsonPrimitive?.content
                        val createdAt = runCatching { Instant.parse(summon.createdAt) }.getOrNull()
                        val readyAt = runCatching { responseTime?.let { Instant.parse(it) } }.getOrNull()
                        if (createdAt != null && readyAt != null) {
                            val mins = ((readyAt.epochSecond - createdAt.epochSecond) / 60).toInt().coerceAtLeast(0)
                            "+$mins"
                        } else null
                    } else null
                    ResponseAvatar(avatarUrl = avatarUrl, tintColor = tint, label = label)
                }
            }
        }
    }
}

@Composable
private fun ResponseAvatar(
    avatarUrl: String?,
    tintColor: Color?,
    borderColor: Color = Color.Transparent,
    borderWidth: androidx.compose.ui.unit.Dp = 0.dp,
    label: String? = null,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(if (borderWidth > 0.dp) Modifier.border(borderWidth, borderColor, RoundedCornerShape(6.dp)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (tintColor != null) {
            Box(modifier = Modifier.fillMaxSize().background(tintColor))
        }
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}
