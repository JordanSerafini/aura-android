package dev.aura.mobile.ui

import dev.aura.mobile.protocol.States
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaloLogicTest {
  @Test fun halo_anime_seulement_quand_aura_ecoute_reflechit_ou_parle() {
    assertTrue(HaloLogic.animated(recording = true, state = null, playing = false, animationsEnabled = true))
    assertTrue(HaloLogic.animated(false, States.THINKING, false, true))
    assertTrue(HaloLogic.animated(false, States.TRANSCRIBING, false, true))
    assertTrue(HaloLogic.animated(false, States.DONE, playing = true, animationsEnabled = true))
    assertFalse(HaloLogic.animated(false, null, false, true))  // repos
    assertFalse(HaloLogic.animated(false, States.DONE, false, true))
    assertFalse(HaloLogic.animated(false, States.SENT, false, true))
  }

  @Test fun halo_statique_si_les_animations_sont_supprimees() {
    assertFalse(HaloLogic.animated(recording = true, state = States.THINKING, playing = true, animationsEnabled = false))
  }
}
