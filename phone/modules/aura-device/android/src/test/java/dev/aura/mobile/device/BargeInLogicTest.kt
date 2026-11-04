package dev.aura.mobile.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInLogicTest {
  @Test fun mode_du_vad_selon_l_etat_et_le_reglage() {
    assertEquals(Vad.Mode.LISTEN, BargeInLogic.modeFor("listening", false))
    assertEquals(Vad.Mode.LISTEN, BargeInLogic.modeFor("listening", true))  // le reglage ne coupe que pendant la reponse
    assertEquals(Vad.Mode.BARGE, BargeInLogic.modeFor("speaking", false))
    assertEquals(Vad.Mode.BARGE, BargeInLogic.modeFor("thinking", false))
    assertEquals(Vad.Mode.MUTED, BargeInLogic.modeFor("speaking", true))
    assertEquals(Vad.Mode.MUTED, BargeInLogic.modeFor("sending", true))
  }

  @Test fun coupure_permise_hors_grace_et_micro_ouvert() {
    assertTrue(BargeInLogic.shouldInterrupt("speaking", false, BargeInLogic.GRACE_MS.toLong()))
    assertTrue("reflexion : pas de lecture, pas de grace", BargeInLogic.shouldInterrupt("thinking", false, -1))
    assertFalse("debut de lecture", BargeInLogic.shouldInterrupt("speaking", false, 100))
    assertFalse("micro coupe pendant qu'Aura parle", BargeInLogic.shouldInterrupt("speaking", true, 5_000))
    assertFalse("l'utilisateur parle deja : rien a couper", BargeInLogic.shouldInterrupt("hearing", false, -1))
  }

  @Test fun pref_absente_garde_la_coupure_a_la_voix() {
    assertFalse(BargeInLogic.muted(null))
    assertFalse(BargeInLogic.muted("0"))
    assertTrue(BargeInLogic.muted("1"))
  }

  @Test fun gardes_dans_les_bornes_annoncees() {
    assertTrue(BargeInLogic.MIN_VOICE_MS in 300..400)
    assertTrue(BargeInLogic.GRACE_MS in 300..1000)
  }
}
