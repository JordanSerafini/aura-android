package dev.aura.mobile.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmSummaryTest {
  private fun req(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }

  private val maman = ConfirmSummary.Resolver { to -> if (to.lowercase() == "maman") "+33612345678" to "Maman" else null }

  @Test fun sms_montre_le_destinataire_resolu_et_le_texte_reel() {
    val s = ConfirmSummary.build("sms_send", req("to" to "maman", "text" to "J'arrive"), maman)
    assertEquals("Envoyer un SMS à Maman (+33612345678) : « J'arrive »", s)
  }

  @Test fun numero_donne_en_clair_reste_visible_avec_son_nom_s_il_existe() {
    val byNumber = ConfirmSummary.Resolver { "+33699999999" to "Camille" }
    assertEquals("Appeler Camille (+33699999999)", ConfirmSummary.build("call", req("to" to "06 99 99 99 99"), byNumber))
    val anon = ConfirmSummary.Resolver { "+33699999999" to null }
    assertEquals("Appeler +33699999999", ConfirmSummary.build("call", req("to" to "0699999999"), anon))
  }

  @Test fun destinataire_non_resolu_est_marque_et_jamais_pris_pour_un_contact() {
    assertEquals("Appeler « Inconnu » (non résolu)", ConfirmSummary.build("call", req("to" to "Inconnu"), maman))
    assertEquals("Appeler « Maman » (non résolu)", ConfirmSummary.build("call", req("to" to "Maman")))  // sans resolveur
    val throwing = ConfirmSummary.Resolver { throw ActionError("ambiguous", "2 contacts") }
    assertEquals("Appeler « Camille » (non résolu)", ConfirmSummary.build("call", req("to" to "Camille"), throwing))
    assertEquals("Appeler destinataire absent", ConfirmSummary.build("call", req(), maman))
  }

  @Test fun le_summary_du_bridge_n_entre_jamais_dans_le_resume() {
    val p = req("to" to "+33600000000", "text" to "virement", "summary" to "Envoyer ok à Maman")
    val s = ConfirmSummary.build("sms_send", p, null)
    assertFalse(s, s.contains("Maman"))
    assertTrue(s, s.contains("+33600000000"))
    assertTrue(s, s.contains("virement"))
  }

  @Test fun texte_tronque_nettoye_et_sans_marques_bidi() {
    val long = ConfirmSummary.build("sms_send", req("to" to "maman", "text" to "x".repeat(500)), maman)
    assertTrue(long, long.length < 200)
    assertTrue(long, long.contains("…"))
    val sneaky = ConfirmSummary.build("notif_reply", req("text" to "ok\n\n‮evil⁦ \u0007 fin"))
    assertEquals("Répondre à une notification : « ok evil fin »", sneaky)
    assertFalse(sneaky.any { it == '\n' || it == '‮' })
  }

  @Test fun actions_sortantes_decrites_depuis_leurs_params() {
    assertEquals("Prendre une photo (caméra arrière)", ConfirmSummary.build("camera_snap", req()))
    assertEquals("Prendre une photo (caméra avant)", ConfirmSummary.build("camera_snap", req("facing" to "front")))
    assertEquals("Envoyer un message whatsapp à Maman (+33612345678) : « salut »",
      ConfirmSummary.build("message_send", req("app" to "whatsapp", "to" to "Maman", "text" to "salut"), maman))
  }

  @Test fun ui_act_decrit_le_geste_reel() {
    assertEquals("Toucher « Envoyer » dans whatsapp", ConfirmSummary.build("ui_act", req("app" to "whatsapp", "op" to "tap", "text" to "Envoyer")))
    assertEquals("Toucher « Retour » dans whatsapp", ConfirmSummary.build("ui_act", req("app" to "whatsapp", "op" to "tap", "desc" to "Retour")))
    assertEquals("Saisir « bonjour » dans whatsapp", ConfirmSummary.build("ui_act", req("app" to "whatsapp", "op" to "type", "text" to "bonjour")))
  }

  @Test fun action_sans_modele_liste_ses_parametres_reels_bornes() {
    assertEquals("Action « alarm_set »", ConfirmSummary.build("alarm_set", req()))
    val s = ConfirmSummary.build("alarm_set", req("hour" to 7, "minute" to 30, "label" to "x".repeat(100), "zz" to 1, "summary" to "faux"))
    assertTrue(s, s.startsWith("Action « alarm_set » (hour = 7, label = "))
    assertTrue(s, s.endsWith(", …)"))
    assertFalse(s, s.contains("faux"))  // trie : « summary » depasse les 3 premiers
  }
}
