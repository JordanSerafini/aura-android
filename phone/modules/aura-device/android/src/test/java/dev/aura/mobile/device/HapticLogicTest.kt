package dev.aura.mobile.device

import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HapticLogicTest {
  @Test fun `les quatre intentions de la PWA ont un effet systeme`() {
    assertEquals(HapticFeedbackConstants.CLOCK_TICK, HapticLogic.constant("tick", 36))
    assertEquals(HapticFeedbackConstants.CONFIRM, HapticLogic.constant("confirm", 36))
    assertEquals(HapticFeedbackConstants.REJECT, HapticLogic.constant("reject", 36))
    assertEquals(HapticFeedbackConstants.LONG_PRESS, HapticLogic.constant("heavy", 36))
  }

  @Test fun `avant Android 11 confirm et reject retombent sur des effets connus`() {
    assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, HapticLogic.constant("confirm", 29))
    assertEquals(HapticFeedbackConstants.LONG_PRESS, HapticLogic.constant("reject", 29))
  }

  @Test fun `une intention inconnue ne joue rien`() {
    assertNull(HapticLogic.constant("buzz", 36))
    assertNull(HapticLogic.constant("", 36))
  }
}
