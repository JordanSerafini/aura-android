package dev.aura.mobile.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLogicTest {
  @Test fun un_tour_parti_d_ici_est_visible_tout_de_suite() {
    assertTrue(LiveLogic.visible(foreign = false, elapsedMs = 0))
    assertEquals(0L, LiveLogic.dueIn(false, 1_000L, 1_000L))
  }

  @Test fun un_tour_etranger_attend_d_etre_long() {
    assertFalse(LiveLogic.visible(true, 0))
    assertFalse(LiveLogic.visible(true, LiveLogic.LONG_MS - 1))
    assertTrue(LiveLogic.visible(true, LiveLogic.LONG_MS))      // frontiere : 90 s pile
    assertTrue(LiveLogic.visible(true, 10 * 60_000L))
  }

  @Test fun delai_avant_apparition() {
    assertEquals(90_000L, LiveLogic.dueIn(true, 10_000L, 10_000L))
    assertEquals(30_000L, LiveLogic.dueIn(true, 10_000L, 70_000L))
    assertEquals(0L, LiveLogic.dueIn(true, 10_000L, 100_000L))   // deja long
    assertEquals(0L, LiveLogic.dueIn(true, 10_000L, 500_000L))   // jamais negatif
  }

  @Test fun arreter_seulement_pour_une_demande_d_ici() {
    assertTrue(LiveLogic.canStop(false, "w42"))
    assertFalse("tour du PC : pas de bouton Arreter", LiveLogic.canStop(true, "w42"))
    assertFalse("id inconnu : rien a annuler", LiveLogic.canStop(false, ""))
  }

  @Test fun titre_distingue_le_pc() {
    assertEquals("Aura · Bash", LiveLogic.title(false, "Bash"))
    assertEquals("Aura sur le PC · Bash", LiveLogic.title(true, "Bash"))
  }

  @Test fun texte_d_attente() {
    assertEquals("Réflexion en cours…", LiveLogic.waitingText(false, 120_000))
    assertEquals("Tâche en cours depuis 1 min", LiveLogic.waitingText(true, 95_000))
    assertEquals("Tâche en cours depuis 3 min", LiveLogic.waitingText(true, 3 * 60_000L + 5_000L))
  }

  @Test fun un_tour_etranger_est_prive_sur_l_ecran_verrouille() {
    assertTrue("tour du PC : texte et outil ne sortent pas sur l'ecran verrouille", LiveLogic.lockScreenPrivate(true))
    assertFalse("demande partie d'ici : comportement d'avant", LiveLogic.lockScreenPrivate(false))
  }

  @Test fun version_publique_neutre_d_un_tour_etranger() {
    assertEquals("Aura travaille…", LiveLogic.PUBLIC_TITLE)
    assertFalse(LiveLogic.PUBLIC_TITLE.contains("PC"))
  }

  @Test fun puce_de_la_barre_d_etat_sans_outil_pour_un_tour_etranger() {
    assertEquals("Bash", LiveLogic.chip(false, "Bash", true))
    assertNull("nom d'outil du PC : pas dans la puce", LiveLogic.chip(true, "Bash", true))
    assertNull(LiveLogic.chip(false, "Bash", false))
    assertNull(LiveLogic.chip(false, null, true))
    assertEquals("Transcriptio", LiveLogic.chip(false, "transcription · vocal", true))  // 12 car. au plus
    assertEquals(12, LiveLogic.chip(false, "UnNomD'OutilTresLong", true)!!.length)
  }

  // ─── run_state (PROTOCOL.md §11.D) ──────────────────────────────────────

  private fun rs(json: String) = LiveLogic.parseRunState(JSONObject(json))

  @Test fun run_state_du_bridge_est_lu() {
    val r = rs("""{"type":"run_state","conv":"c1","state":"running","elapsed_s":125,"step":"Bash · rsync -a /data /backup"}""")!!
    assertEquals("c1", r.conv)
    assertEquals(125L, r.elapsedS)
    assertEquals("Bash · rsync -a /data /backup", r.step)
  }

  @Test fun run_state_sans_conv_ou_sans_duree_ou_fou_est_ignore() {
    assertNull(rs("""{"elapsed_s":40,"step":"x"}"""))
    assertNull(rs("""{"conv":"","elapsed_s":40}"""))
    assertNull(rs("""{"conv":"c1","step":"x"}"""))
    assertNull(rs("""{"conv":"c1","elapsed_s":"40"}"""))
    assertNull(rs("""{"conv":"c1","elapsed_s":-1}"""))
    assertNull(rs("""{"conv":"c1","elapsed_s":${LiveLogic.MAX_ELAPSED_S + 1}}"""))
    assertEquals(LiveLogic.MAX_ELAPSED_S, rs("""{"conv":"c1","elapsed_s":${LiveLogic.MAX_ELAPSED_S}}""")!!.elapsedS)
  }

  @Test fun etape_outil_redaction_ou_reflexion() {
    assertEquals(LiveLogic.StepKind.TOOL, LiveLogic.stepKind("Bash · rsync -a /data /backup"))
    assertEquals(LiveLogic.StepKind.TOOL, LiveLogic.stepKind("WebFetch · https://example.org"))
    assertEquals(LiveLogic.StepKind.WRITING, LiveLogic.stepKind("rédaction de la réponse"))
    assertEquals(LiveLogic.StepKind.THINKING, LiveLogic.stepKind("réflexion"))
    assertEquals(LiveLogic.StepKind.THINKING, LiveLogic.stepKind(""))
    assertEquals(LiveLogic.StepKind.WRITING, LiveLogic.stepKind("  Rédaction de la réponse "))
  }

  @Test fun etape_sans_texte_du_bridge_ne_casse_rien() {
    val r = rs("""{"conv":"c1","elapsed_s":31}""")!!
    assertEquals("", r.step)
    assertEquals(LiveLogic.StepKind.THINKING, LiveLogic.stepKind(r.step))
  }

  @Test fun etape_de_tiers_sans_controles_et_bornee() {
    val r = rs("""{"conv":"c1","elapsed_s":31,"step":"Bash · ${"x".repeat(300)}\u0007\n"}""")!!
    assertTrue(r.step.length <= 120)
    assertTrue(r.step.none { it.code < 32 })
  }

  @Test fun un_tour_non_vu_commencer_retrouve_sa_vraie_duree() {
    // telephone reconnecte : le tour dure depuis 200 s, le telephone ne le sait que maintenant
    val now = 1_000_000_000L
    assertEquals(now - 200_000L, LiveLogic.startedFrom(currentMs = now, nowMs = now, elapsedS = 200))
    // visible comme tour etranger des qu'il a 90 s, sans minuterie locale
    assertTrue(LiveLogic.visible(true, now - LiveLogic.startedFrom(now, now, 95)))
    assertFalse(LiveLogic.visible(true, now - LiveLogic.startedFrom(now, now, 60)))
  }

  @Test fun un_tour_suivi_depuis_son_premier_mot_ne_rajeunit_jamais() {
    val now = 1_000_000_000L
    val started = now - 300_000L   // le telephone le suit depuis 300 s
    assertEquals(started, LiveLogic.startedFrom(started, now, 120))   // le bridge dit 120 s : on garde le plus ancien
  }
}
