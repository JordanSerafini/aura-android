package dev.aura.mobile.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class LimitLogicTest {
  // 2026-10-01 19:30:00 Europe/Paris (CEST) = 17:30:00 UTC
  private val resets = 1_790_875_800L
  private fun limit(json: String) = LimitLogic.parse(JSONObject(json))!!

  private val SESSION = """{"type":"limit_state","conv":"c1","kind":"session","resets_at":$resets,"retry_at":${resets + 45},
    "message":"Limite de session atteinte : Aura reprend à 19:30 (heure de Paris) et relancera ta dernière demande.","retrying":false}"""

  @Test fun limite_de_session_avec_reprise() {
    val e = limit(SESSION)
    assertEquals("c1", e.conv)
    assertEquals("session", e.kind)
    assertEquals(resets, e.resetsAt)
    assertEquals(resets + 45, e.retryAt)
    assertFalse(e.retrying)
    assertTrue(LimitLogic.canCancel(e))
  }

  @Test fun titre_dit_l_heure_de_reprise_en_heure_de_paris() {
    val e = limit(SESSION)
    assertEquals("Limite atteinte — Aura reprend à 19:30", LimitLogic.title(e))
    // meme message, telephone reglé ailleurs : l'heure reste celle de Paris, comme le dit le bridge
    assertEquals("17:30", LimitLogic.hhmm(resets, ZoneId.of("UTC")))
    assertEquals("19:30", LimitLogic.hhmm(resets))
    assertTrue(LimitLogic.body(e).startsWith("Limite de session atteinte"))
  }

  @Test fun l_heure_affichee_est_celle_de_la_limite_pas_celle_des_45_secondes_apres() {
    val e = limit("""{"conv":"c1","kind":"session","resets_at":${resets},"retry_at":${resets + 45},"message":""}""")
    assertEquals("Limite atteinte — Aura reprend à 19:30", LimitLogic.title(e))
    val noReset = limit("""{"conv":"c1","kind":"session","resets_at":null,"retry_at":${resets + 45}}""")
    assertEquals("Limite atteinte — Aura reprend à 19:30", LimitLogic.title(noReset))
  }

  @Test fun limite_mensuelle_sans_reprise_ni_bouton() {
    val e = limit("""{"conv":"c1","kind":"monthly","resets_at":null,"retry_at":null,
      "message":"Limite mensuelle de dépense de l'organisation atteinte : Aura ne relance pas toute seule.","retrying":false}""")
    assertEquals("monthly", e.kind)
    assertNull(e.retryAt)
    assertFalse(LimitLogic.canCancel(e))
    assertEquals("Limite atteinte", LimitLogic.title(e))
    assertTrue(LimitLogic.body(e).contains("ne relance pas"))
  }

  @Test fun reprise_en_cours_met_la_notification_a_jour_sans_bouton() {
    val e = limit("""{"conv":"c1","kind":"session","resets_at":$resets,"retry_at":${resets + 45},"retrying":true,"id":"m9"}""")
    assertTrue(e.retrying)
    assertFalse("rien a annuler : la reprise est partie", LimitLogic.canCancel(e))
    assertEquals("Aura reprend maintenant", LimitLogic.title(e))
  }

  @Test fun reprise_deja_tentee_dit_le_message_du_bridge() {
    val e = limit("""{"conv":"c1","kind":"session","retry_at":null,"message":"La reprise automatique n'a pas suffi (limite toujours active) : renvoie ta demande plus tard."}""")
    assertFalse(LimitLogic.canCancel(e))
    assertTrue(LimitLogic.body(e).startsWith("La reprise automatique n'a pas suffi"))
  }

  @Test fun repli_de_texte_sans_message() {
    assertEquals("Aura relancera ta dernière demande.", LimitLogic.body(limit("""{"conv":"c1","retry_at":$resets}""")))
    assertEquals("Aura ne relance pas toute seule.", LimitLogic.body(limit("""{"conv":"c1","retry_at":null}""")))
  }

  @Test fun conv_obligatoire_et_bien_formee() {
    assertNull(LimitLogic.parse(JSONObject("""{"kind":"session","retry_at":1}""")))
    assertNull(LimitLogic.parse(JSONObject("""{"conv":"a/b"}""")))
    assertNull(LimitLogic.parse(JSONObject("""{"conv":"${"c".repeat(65)}"}""")))
  }

  @Test fun heures_illisibles_donnent_null_et_millisecondes_sont_tolerees() {
    assertNull(limit("""{"conv":"c1","retry_at":"demain"}""").retryAt)
    assertNull(limit("""{"conv":"c1","retry_at":-5}""").retryAt)
    assertNull(limit("""{"conv":"c1","retry_at":0}""").retryAt)
    assertEquals(resets, limit("""{"conv":"c1","resets_at":${resets * 1000}}""").resetsAt)
  }

  @Test fun kind_inconnu_vaut_session_et_retrying_est_strict() {
    assertEquals("session", limit("""{"conv":"c1","kind":"autre"}""").kind)
    assertFalse(limit("""{"conv":"c1","retrying":"true"}""").retrying)
  }

  @Test fun message_de_tiers_borne_et_en_texte_seul() {
    val e = limit("""{"conv":"c1","message":"${"m".repeat(900)}\n‮"}""")
    assertTrue(e.message.length <= LimitLogic.MESSAGE_MAX)
    assertFalse(e.message.contains('\n'))
  }
}
