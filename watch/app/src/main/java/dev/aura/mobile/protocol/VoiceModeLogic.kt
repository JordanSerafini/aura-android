package dev.aura.mobile.protocol

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * `voice_mode` (PROTOCOL.md) : `auto` = lu à voix haute sauf pendant les heures de travail
 * (jours ISO 1 = lundi ... 7 = dimanche, créneau [start, end[) ; `voice` = toujours ; `text` = jamais.
 */
object VoiceModeLogic {
  fun shouldSpeak(settings: WatchSettings, now: LocalDateTime): Boolean = when (settings.voiceMode) {
    VoiceMode.VOICE -> true
    VoiceMode.TEXT -> false
    else -> !isWorkTime(settings.workHours, now)
  }

  fun isWorkTime(hours: WorkHours, now: LocalDateTime): Boolean {
    if (now.dayOfWeek.value !in hours.days) return false
    val start = parseTime(hours.start) ?: return false
    val end = parseTime(hours.end) ?: return false
    val t = now.toLocalTime()
    return if (start <= end) t >= start && t < end else t >= start || t < end
  }

  fun parseTime(value: String): LocalTime? = runCatching {
    val (h, m) = value.trim().split(":").map { it.toInt() }
    LocalTime.of(h, m)
  }.getOrNull()
}
