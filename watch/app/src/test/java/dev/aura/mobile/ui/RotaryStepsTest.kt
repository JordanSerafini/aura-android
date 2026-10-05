package dev.aura.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RotaryStepsTest {
  @Test
  fun lowResBezelOneStepPerDetent() {
    val r = RotarySteps(lowRes = true)
    assertEquals(1, r.feed(12f))
    assertEquals(-1, r.feed(-200f))
    assertEquals(0, r.feed(0f))
  }

  @Test
  fun smoothCrownAccumulates() {
    val r = RotarySteps(lowRes = false, stepPx = 48f)
    assertEquals(0, r.feed(20f))
    assertEquals(0, r.feed(20f))
    assertEquals(1, r.feed(20f)) // 60 px -> 1 pas, reste 12
    assertEquals(0, r.feed(-20f)) // changement de sens : le reste est oublié
    assertEquals(-1, r.feed(-30f))
  }

  @Test
  fun bigEventIsOneStep() {
    assertEquals(1, RotarySteps(lowRes = false, stepPx = 48f).feed(150f))
  }
}
