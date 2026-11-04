package dev.aura.mobile.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipLogicTest {
  private val now = 1_790_000_000_000L

  @Test fun `un texte copie ailleurs il y a 2 min est propose une fois`() {
    val stamp = now - 120_000
    assertEquals(120L, ClipLogic.hintAge(stamp, 0, now, true, "label"))
    // deja vu : plus propose
    assertNull(ClipLogic.hintAge(stamp, ClipLogic.nextSeen(stamp, 0), now, true, "label"))
  }

  @Test fun `nos propres copies, une image et un vieux texte ne sont jamais proposes`() {
    assertNull(ClipLogic.hintAge(now - 1000, 0, now, true, ClipLogic.OWN_LABEL))
    assertNull(ClipLogic.hintAge(now - 1000, 0, now, false, null))
    assertNull(ClipLogic.hintAge(now - ClipLogic.MAX_AGE_MS - 1, 0, now, true, null))
    assertNull(ClipLogic.hintAge(0, 0, now, true, null))
  }

  @Test fun `une horloge qui recule ne propose rien`() {
    assertNull(ClipLogic.hintAge(now + 60_000, 0, now, true, null))
  }

  @Test fun `le repere ne recule jamais`() {
    assertEquals(500L, ClipLogic.nextSeen(100, 500))
    assertEquals(900L, ClipLogic.nextSeen(900, 500))
  }

  @Test fun `copy_text de la page suit les planchers de clipboard_set`() {
    assertNull(ClipLogic.copyRefusal("Voici la reponse", PauseMode.OFF))
    assertEquals("paiement", ClipLogic.copyRefusal("IBAN FR76 3000 6000 0112 3456 7890 189", PauseMode.OFF))
    assertEquals("pause", ClipLogic.copyRefusal("Voici la reponse", PauseMode.ALL))
  }

  @Test fun `clip_read de la page est refuse pendant une pause Tout`() {
    assertTrue(ClipLogic.readAllowed(PauseMode.OFF))
    assertFalse(ClipLogic.readAllowed(PauseMode.ALL))
  }
}
