package com.kvi.osquio.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kvi.osquio.data.model.SummonHistory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault())

@Composable
fun HistoryScreen(vm: HistoryViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.load() }

    when (val s = state) {
        is HistoryUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is HistoryUiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(s.message, color = MaterialTheme.colorScheme.error)
        }
        is HistoryUiState.Calendar -> when {
            s.selectedSummon != null -> HistoryDetailScreen(s.selectedSummon, onBack = { vm.back() })
            s.selectedDay != null -> HistoryDayScreen(s.selectedDay, s.dayDetail,
                onSelect = { vm.selectSummon(it) }, onBack = { vm.back() })
            else -> CalendarView(s.summonsPerDay, onSelectDay = { vm.selectDay(it) })
        }
    }
}

@Composable
private fun CalendarView(summonsPerDay: Map<LocalDate, Int>, onSelectDay: (LocalDate) -> Unit) {
    var displayMonth by remember { mutableStateOf(YearMonth.now()) }
    val today = LocalDate.now()

    Column(modifier = Modifier.fillMaxSize().padding(top = 48.dp, bottom = 8.dp)) {
        Text("History", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp))
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
        val totalRows = ((cells.size + 6) / 7)

        // Day-of-week header
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

        // Grid body fills remaining space
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
private fun HistoryDayScreen(
    day: LocalDate,
    summons: List<SummonHistory>,
    onSelect: (SummonHistory) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(dateFmt.format(day.atStartOfDay(ZoneId.systemDefault())),
                style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(8.dp))
        if (summons.isEmpty()) {
            Text("No summons on this day.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn {
                items(summons) { summon ->
                    ListItem(
                        headlineContent = { Text("Game at ${timeFmt.format(Instant.parse(summon.gameTime))}") },
                        supportingContent = { Text(summon.status.replaceFirstChar { it.uppercase() }) },
                        modifier = Modifier.clickable { onSelect(summon) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun HistoryDetailScreen(summon: SummonHistory, onBack: () -> Unit) {
    val respondents = summon.snapshot["respondents"] as? JsonArray ?: return
    val nonRespondents = summon.snapshot["non_respondents"] as? JsonArray ?: return

    Column(modifier = Modifier.fillMaxSize().padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Summon detail", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(8.dp))
        Text("Game time: ${timeFmt.format(Instant.parse(summon.gameTime))}")
        Text("Status: ${summon.status.replaceFirstChar { it.uppercase() }}")
        Spacer(Modifier.height(16.dp))

        Text("Responses", style = MaterialTheme.typography.titleSmall)
        LazyColumn {
            items(respondents.size) { i ->
                val r = respondents[i].jsonObject
                val name = r["display_name"]?.jsonPrimitive?.content ?: "?"
                val avatarUrl = r["avatar_url"]?.jsonPrimitive?.content
                val response = r["response"]?.jsonPrimitive?.content ?: "?"
                val responseTime = r["response_time"]?.jsonPrimitive?.content
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = {
                        val label = when (response) {
                            "yes" -> "Yes"
                            "no" -> "No"
                            "yes_at_time" -> "Yes at ${responseTime?.let { timeFmt.format(Instant.parse(it)) } ?: "?"}"
                            else -> response
                        }
                        Text(label)
                    },
                    leadingContent = {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp).clip(CircleShape),
                        )
                    }
                )
            }
            if (nonRespondents.isNotEmpty()) {
                item {
                    Text("No response", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
                items(nonRespondents.size) { i ->
                    val nr = nonRespondents[i].jsonObject
                    val name = nr["display_name"]?.jsonPrimitive?.content ?: "?"
                    val avatarUrl = nr["avatar_url"]?.jsonPrimitive?.content
                    ListItem(
                        headlineContent = { Text(name) },
                        leadingContent = {
                            AsyncImage(
                                model = avatarUrl,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                            )
                        }
                    )
                }
            }
        }
    }
}
