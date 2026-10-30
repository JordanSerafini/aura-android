package dev.aura.mobile.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Revue securite du 27/10, N3 : une pause posee par un geste SUR le telephone (notification, ecran Appareil, montre)
 * est un plancher. Le bridge peut la durcir ou l'allonger, jamais la lever ni l'adoucir avant son echeance.
 */
class PauseFloorTest {
  private val now = 1_790_000_000L
  private val h = 3600L

  private fun local(mode: String, until: Long) = PauseLogic.localSet(mode, until, "notification", 1L)
  private fun remote(mode: String, until: Long = 0L) = PauseState(mode, until, "desktop")

  @Test fun sans_plancher_le_bridge_fait_foi_meme_pour_lever() {
    val st = PauseState(PauseMode.APPS, now + h, "desktop")  // pause venue du bridge (PC) : pas de plancher
    val next = PauseLogic.adoptable(st, remote(PauseMode.OFF), now)
    assertEquals(PauseMode.OFF, next.effectiveMode(now))
  }

  @Test fun une_levee_distante_ne_leve_pas_la_pause_du_telephone() {
    val next = PauseLogic.adoptable(local(PauseMode.APPS, now + h), remote(PauseMode.OFF), now)
    assertEquals(PauseMode.APPS, next.mode)
    assertEquals(now + h, next.until)
  }

  @Test fun un_adoucissement_distant_est_refuse() {
    val next = PauseLogic.adoptable(local(PauseMode.ALL, now + h), remote(PauseMode.APPS, now + h), now)
    assertEquals(PauseMode.ALL, next.mode)
  }

  @Test fun une_echeance_plus_courte_est_refusee() {
    val next = PauseLogic.adoptable(local(PauseMode.APPS, now + h), remote(PauseMode.APPS, now + 60), now)
    assertEquals(now + h, next.until)
    val sansFin = PauseLogic.adoptable(local(PauseMode.APPS, 0L), remote(PauseMode.APPS, now + h), now)
    assertEquals(0L, sansFin.until)
  }

  @Test fun le_bridge_peut_durcir_ou_allonger_et_le_plancher_reste() {
    val all = PauseLogic.adoptable(local(PauseMode.APPS, now + h), remote(PauseMode.ALL, now + 60), now)
    assertEquals(PauseMode.ALL, all.mode)
    assertEquals(now + 60, all.until)
    // la pause du PC se leve depuis le PC : on retombe sur le plancher du telephone, pas sur off
    val back = PauseLogic.adoptable(all, remote(PauseMode.OFF), now + 120)
    assertEquals(PauseMode.APPS, back.mode)
    assertEquals(now + h, back.until)
    val longer = PauseLogic.adoptable(local(PauseMode.APPS, now + h), remote(PauseMode.APPS, now + 2 * h), now)
    assertEquals(now + 2 * h, longer.until)
  }

  @Test fun a_l_echeance_du_bridge_on_retombe_sur_le_plancher() {
    val all = PauseLogic.adoptable(local(PauseMode.APPS, now + h), remote(PauseMode.ALL, now + 60), now)
    val after = PauseLogic.afterExpiry(all, now + 61)
    assertEquals(PauseMode.APPS, after.mode)
    assertEquals(PauseMode.OFF, PauseLogic.afterExpiry(local(PauseMode.APPS, now + h), now + h).mode)
  }

  @Test fun plancher_echu_ou_tout_proche_de_l_echeance_le_bridge_peut_lever() {
    assertEquals(PauseMode.OFF, PauseLogic.adoptable(local(PauseMode.APPS, now + h), remote(PauseMode.OFF), now + h + 1).mode)
    // derive d'horloge : a moins de 2 min de l'echeance, renvoyer un until deja passe pour le bridge ne sert a rien
    assertEquals(PauseMode.OFF, PauseLogic.adoptable(local(PauseMode.APPS, now + h), remote(PauseMode.OFF), now + h - 60).mode)
  }

  @Test fun une_levee_locale_supprime_le_plancher() {
    val lifted = PauseLogic.localSet(PauseMode.OFF, 0L, "téléphone", 2L)
    assertEquals(PauseMode.APPS, PauseLogic.adoptable(lifted, remote(PauseMode.APPS, now + h), now).mode)
    assertEquals(PauseMode.OFF, PauseLogic.adoptable(PauseLogic.adoptable(lifted, remote(PauseMode.APPS, now + h), now),
      remote(PauseMode.OFF), now).mode)
  }

  @Test fun le_plancher_survit_aux_prefs() {
    val st = local(PauseMode.APPS, now + h)
    val back = PauseState.fromJson(st.toJson().toString())
    assertEquals(PauseMode.APPS, back.floorMode)
    assertEquals(now + h, back.floorUntil)
  }

  @Test fun pause_kt_passe_par_le_plancher_au_message_et_au_welcome() {
    val src = File("src/main/java/dev/aura/mobile/device/Pause.kt").readText()
    assertTrue(Regex("""fun adoptRemote[\s\S]{0,300}PauseLogic\.adoptable\(""").containsMatchIn(src))
    assertTrue(Regex("""fun onBridge[\s\S]{0,300}adoptRemote\(""").containsMatchIn(src))
    assertTrue(Regex("""ADOPT_REMOTE -> adoptRemote\(""").containsMatchIn(src))
    assertTrue("plus d'adoption brute d'un etat distant", !src.contains("adopt(if (remote.active(now))"))
    assertTrue(Regex("""fun set\([\s\S]{0,300}PauseLogic\.localSet\(""").containsMatchIn(src))
    assertTrue(Regex("""fun expire\([\s\S]{0,300}PauseLogic\.afterExpiry\(""").containsMatchIn(src))
  }
}
