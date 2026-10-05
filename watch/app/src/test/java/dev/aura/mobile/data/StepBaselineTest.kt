package dev.aura.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StepBaselineTest {
  @Test
  fun firstReadingOfTheDaySetsBaseline() {
    val (steps, base) = StepBaseline.compute(StepBaseline("2026-09-25", 1000), "2026-09-26", 5000)
    assertEquals(0L, steps)
    assertEquals(StepBaseline("2026-09-26", 5000), base)
  }

  @Test
  fun sameDaySubtractsBaseline() {
    val (steps, base) = StepBaseline.compute(StepBaseline("2026-09-26", 5000), "2026-09-26", 8200)
    assertEquals(3200L, steps)
    assertEquals(StepBaseline("2026-09-26", 5000), base)
  }

  @Test
  fun rebootResetsBaselineToZero() {
    val (steps, base) = StepBaseline.compute(StepBaseline("2026-09-26", 5000), "2026-09-26", 300)
    assertEquals(300L, steps)
    assertEquals(StepBaseline("2026-09-26", 0), base)
  }
}
