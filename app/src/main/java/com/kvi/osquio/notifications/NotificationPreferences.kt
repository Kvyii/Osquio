package com.kvi.osquio.notifications

import com.kvi.osquio.R
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

enum class LoudSound(val resId: Int, val key: String, val label: String) {
    GAME_READY(R.raw.sound_game_ready, "game_ready", "Dota Game Ready"),
    READY_CHECK(R.raw.sound_ready_check, "ready_check", "Dota Ready Check"),
}

enum class DiscreteSound(val resId: Int, val key: String, val label: String) {
    TEAMS(R.raw.sound_teams, "teams", "Teams message"),
    SLACK(R.raw.sound_slack, "slack", "Slack message"),
}

private val WORK_HOURS_START: LocalTime = LocalTime.of(8, 30)
private val WORK_HOURS_END: LocalTime = LocalTime.of(17, 30)
private val WORK_HOURS_DAYS = DayOfWeek.MONDAY..DayOfWeek.FRIDAY

@Serializable
data class DndWindow(
    val enabled: Boolean = true,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val days: Set<Int> = emptySet(), // java.time.DayOfWeek.value: Mon=1..Sun=7
) {
    fun contains(now: LocalTime, day: DayOfWeek): Boolean {
        if (!enabled || day.value !in days) return false
        val start = LocalTime.of(startHour, startMinute)
        val end = LocalTime.of(endHour, endMinute)
        return if (start <= end) {
            now >= start && now < end
        } else {
            // overnight window, e.g. 22:00-06:00
            now >= start || now < end
        }
    }
}

@Serializable
data class NotificationPreferences(
    val soundEnabled: Boolean = false,
    val loudSoundKey: String = LoudSound.GAME_READY.key,
    val discreteModeEnabled: Boolean = false,
    val discreteSoundKey: String = DiscreteSound.TEAMS.key,
    val dndWindows: List<DndWindow> = emptyList(),
) {
    val loudSound: LoudSound
        get() = LoudSound.entries.find { it.key == loudSoundKey } ?: LoudSound.GAME_READY

    val discreteSound: DiscreteSound
        get() = DiscreteSound.entries.find { it.key == discreteSoundKey } ?: DiscreteSound.TEAMS
}

sealed interface SoundDecision {
    data class Loud(val soundResId: Int) : SoundDecision
    data class Discrete(val soundResId: Int) : SoundDecision
    data object Silent : SoundDecision
}

fun NotificationPreferences.decide(
    now: LocalTime = LocalTime.now(),
    day: DayOfWeek = LocalDate.now().dayOfWeek,
): SoundDecision {
    if (!soundEnabled) return SoundDecision.Silent
    if (dndWindows.any { it.contains(now, day) }) return SoundDecision.Silent
    if (discreteModeEnabled && day in WORK_HOURS_DAYS && now >= WORK_HOURS_START && now < WORK_HOURS_END) {
        return SoundDecision.Discrete(discreteSound.resId)
    }
    return SoundDecision.Loud(loudSound.resId)
}
