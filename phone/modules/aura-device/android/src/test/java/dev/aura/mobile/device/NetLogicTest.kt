package dev.aura.mobile.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetLogicTest {
  @Test fun premierReseauNeRelancePas() {
    assertFalse(NetLogic.changed(null, "wifi"))
  }

  @Test fun memeReseauNeRelancePas() {
    assertFalse(NetLogic.changed("wifi", "wifi"))
  }

  @Test fun autreReseauRelance() {
    assertTrue(NetLogic.changed("wifi", "4g"))
  }

  @Test fun controleDeVieToutesLes5Minutes() {
    assertEquals(5 * 60_000L, WatchdogLogic.ONLINE_CHECK_MS)
  }
}
