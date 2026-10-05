package dev.aura.mobile.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinLogicTest {
  private fun done(json: String) = FinLogic.parse(JSONObject(json))

  @Test fun task_done_lu() {
    val e = done("""{"type":"task_done","conv":"c1","title":"Migration des tiers","ok":true,"summary":"913 sur 920 importés.","elapsed_s":125}""")!!
    assertEquals("c1", e.conv)
    assertTrue(e.ok)
    assertEquals(125, e.elapsedS)
    assertEquals("Aura a fini : Migration des tiers", FinLogic.title(e))
    assertEquals("913 sur 920 importés.", FinLogic.body(e))
  }

  @Test fun echec_dit_qu_aura_n_a_pas_pu_finir() {
    val e = done("""{"conv":"c1","title":"Sauvegarde","ok":false,"summary":"disque plein","elapsed_s":40}""")!!
    assertFalse(e.ok)
    assertEquals("Aura n'a pas pu finir : Sauvegarde", FinLogic.title(e))
  }

  @Test fun sans_resume_le_corps_donne_la_duree() {
    val e = done("""{"conv":"c1","title":"x","ok":true,"summary":"","elapsed_s":125}""")!!
    assertEquals("Terminé en 2 min 05", FinLogic.body(e))
    assertEquals("Terminé en 45 s", FinLogic.body(done("""{"conv":"c1","ok":true,"elapsed_s":45}""")!!))
  }

  @Test fun duree_lisible() {
    assertEquals("0 s", FinLogic.elapsedLabel(0))
    assertEquals("59 s", FinLogic.elapsedLabel(59))
    assertEquals("1 min 00", FinLogic.elapsedLabel(60))
    assertEquals("62 min 07", FinLogic.elapsedLabel(62 * 60 + 7))
  }

  @Test fun conv_obligatoire_et_bien_formee() {
    assertNull(done("""{"title":"x","ok":true}"""))
    assertNull(done("""{"conv":"","title":"x"}"""))
    assertNull(done("""{"conv":"../../etc","title":"x"}"""))
    assertNull(done("""{"conv":"c1 c2","title":"x"}"""))
    assertNull(done("""{"conv":"${"c".repeat(65)}","title":"x"}"""))
  }

  @Test fun titre_et_resume_de_tiers_en_texte_seul() {
    val e = done("""{"conv":"c1","title":"Titre‮\n${"t".repeat(300)}","ok":true,"summary":"Ligne 1\nLigne 2 ​ ${"s".repeat(400)}"}""")!!
    assertTrue(e.title.length <= FinLogic.TITLE_MAX)
    assertTrue(e.summary.length <= FinLogic.SUMMARY_MAX)
    assertFalse(e.title.contains('‮'))
    assertFalse(e.summary.contains('\n'))
    assertEquals("Aura", done("""{"conv":"c1","title":"","ok":true}""")!!.title)
  }

  @Test fun ok_absent_vaut_reussi_seul_false_est_un_echec() {
    assertTrue(done("""{"conv":"c1"}""")!!.ok)
    assertTrue(done("""{"conv":"c1","ok":"false"}""")!!.ok)
    assertFalse(done("""{"conv":"c1","ok":false}""")!!.ok)
  }

  @Test fun duree_negative_ou_absente_vaut_zero() {
    assertEquals(0, done("""{"conv":"c1","elapsed_s":-5}""")!!.elapsedS)
    assertEquals(0, done("""{"conv":"c1"}""")!!.elapsedS)
  }

  // ─── Pas de heads-up quand la reponse est sous les yeux ──────────────────

  @Test fun pas_de_heads_up_si_l_app_montre_deja_le_chat() {
    assertFalse(FinLogic.shouldShow(appForeground = true, screen = "Aura"))
    assertFalse(FinLogic.shouldShow(appForeground = true, screen = "Talk"))
    assertTrue(FinLogic.shouldShow(appForeground = true, screen = "Réglages"))   // au premier plan mais ailleurs
    assertTrue(FinLogic.shouldShow(appForeground = false, screen = "Aura"))      // app en arriere-plan
  }

  // ─── Pas de doublon avec la notification aura-fin du bridge ─────────────

  @Test fun une_aura_fin_recente_de_la_meme_conversation_vaut_le_heads_up() {
    val d = FinDedup()
    d.onBridgeFin("c1", 1_000_000L)
    assertTrue(d.duplicate("c1", 1_000_000L + 2_500L))   // task_done arrive d'abord, aura-fin dans la seconde : on s'efface
    assertTrue(d.duplicate("c1", 1_000_000L + FinLogic.DEDUP_WINDOW_MS))   // frontiere : 60 s pile
    assertFalse(d.duplicate("c1", 1_000_000L + FinLogic.DEDUP_WINDOW_MS + 1))
  }

  @Test fun une_autre_conversation_n_est_pas_un_doublon() {
    val d = FinDedup()
    d.onBridgeFin("c1", 1_000L)
    assertFalse(d.duplicate("c2", 1_500L))
  }

  @Test fun sans_aura_fin_le_heads_up_part() {
    assertFalse(FinDedup().duplicate("c1", 5_000L))
  }

  @Test fun la_derniere_aura_fin_fait_foi() {
    val d = FinDedup()
    d.onBridgeFin("c1", 1_000L)
    d.onBridgeFin("c1", 200_000L)
    assertTrue(d.duplicate("c1", 230_000L))
  }

  @Test fun le_delai_laisse_le_temps_a_aura_fin_d_arriver() {
    assertTrue(FinLogic.GRACE_MS >= 1_000L)
    assertTrue(FinLogic.GRACE_MS < 10_000L)
  }
}
