package dev.aura.mobile.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class VoiceModeLogicTest {
  private val auto = WatchSettings()

  // 2026-09-28 est un lundi, 2026-10-03 un samedi.
  private fun at(day: Int, h: Int, m: Int): LocalDateTime = LocalDateTime.of(2026, 9, 28, h, m).plusDays(day.toLong())

  @Test
  fun autoIsTextDuringWorkHours() {
    assertFalse(VoiceModeLogic.shouldSpeak(auto, at(0, 8, 30)))
    assertFalse(VoiceModeLogic.shouldSpeak(auto, at(2, 12, 0)))
    assertFalse(VoiceModeLogic.shouldSpeak(auto, at(4, 17, 29)))
  }

  @Test
  fun autoSpeaksOutsideWorkHours() {
    assertTrue(VoiceModeLogic.shouldSpeak(auto, at(0, 8, 29)))
    assertTrue(VoiceModeLogic.shouldSpeak(auto, at(0, 17, 30)))
    assertTrue(VoiceModeLogic.shouldSpeak(auto, at(5, 11, 0)))
    assertTrue(VoiceModeLogic.shouldSpeak(auto, at(6, 11, 0)))
  }

  @Test
  fun forcedModesIgnoreSchedule() {
    assertTrue(VoiceModeLogic.shouldSpeak(WatchSettings(VoiceMode.VOICE), at(1, 10, 0)))
    assertFalse(VoiceModeLogic.shouldSpeak(WatchSettings(VoiceMode.TEXT), at(5, 20, 0)))
  }

  @Test
  fun unknownModeBehavesLikeAuto() {
    assertFalse(VoiceModeLogic.shouldSpeak(WatchSettings("bizarre"), at(1, 10, 0)))
  }

  @Test
  fun overnightRangeAndBadTimes() {
    val night = WorkHours(days = (1..7).toList(), start = "22:00", end = "06:00")
    assertTrue(VoiceModeLogic.isWorkTime(night, at(0, 23, 0)))
    assertTrue(VoiceModeLogic.isWorkTime(night, at(0, 5, 59)))
    assertFalse(VoiceModeLogic.isWorkTime(night, at(0, 12, 0)))
    assertFalse(VoiceModeLogic.isWorkTime(WorkHours(start = "n'importe", end = "17:30"), at(0, 10, 0)))
  }
}
