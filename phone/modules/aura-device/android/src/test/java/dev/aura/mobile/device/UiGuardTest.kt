package dev.aura.mobile.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UiGuardTest {
  private fun req(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }

  private fun codeOf(block: () -> Unit): String {
    try {
      block()
    } catch (e: ActionError) {
      return e.code
    }
    fail("ActionError attendue")
    return ""
  }

  private val wa = UiGuard.DEFAULT_WHITELIST

  // ─── Liste noire ────────────────────────────────────────────────────────

  @Test fun apps_sensibles_refusees() {
    for (p in listOf(
      "com.boursorama.android.clients", "fr.creditagricole.androidapp", "com.paypal.android.p2pmobile",
      "com.revolut.revolut", "com.android.vending", "com.android.settings", "com.android.systemui",
      "com.x8bit.bitwarden", "com.google.android.apps.authenticator2", "com.samsung.android.spay",
      "com.google.android.apps.walletnfcrel", "com.lastpass.lpandroid", "dev.aura.mobile",
      "com.google.android.gms", "com.google.android.permissioncontroller", "COM.ANDROID.VENDING",
    )) assertTrue(p, UiGuard.blocked(p))
  }

  @Test fun apps_ordinaires_non_bloquees() {
    for (p in listOf("com.whatsapp", "org.telegram.messenger", "com.google.android.apps.maps", "com.spotify.music",
      "com.android.chrome")) assertFalse(p, UiGuard.blocked(p))
  }

  @Test fun liste_noire_gagne_meme_en_liste_blanche() {
    // liste blanche passee brute, contenant des apps sensibles : refus quand meme, et AVANT le controle de la liste
    val forced = listOf("com.android.settings", "com.boursorama.android.clients", "com.whatsapp")
    assertEquals("app_blocked", codeOf { UiGuard.plan(req("app" to "com.android.settings", "op" to "back"), forced) })
    assertEquals("app_blocked", codeOf { UiGuard.plan(req("app" to "com.boursorama.android.clients", "op" to "home"), forced) })
    assertEquals("app_blocked", codeOf { UiGuard.plan(req("app" to "revolut", "op" to "tap", "text" to "x"), forced) })
  }

  @Test fun normalisation_retire_les_paquets_sensibles_et_les_formes_invalides() {
    val out = UiGuard.normalize(listOf("com.android.vending", "com.whatsapp", "COM.WHATSAPP ", "pas un paquet", "", "com.bitwarden.x",
      "org.telegram.messenger"))
    assertEquals(listOf("com.whatsapp", "org.telegram.messenger"), out)
  }

  @Test fun liste_blanche_bornee() {
    val many = (1..80).map { "com.exemple.app$it" }
    assertEquals(UiGuard.WHITELIST_MAX, UiGuard.normalize(many).size)
  }

  // ─── Liste blanche ──────────────────────────────────────────────────────

  @Test fun defaut_whatsapp_seul() {
    assertEquals(listOf("com.whatsapp", "com.whatsapp.w4b"), UiGuard.DEFAULT_WHITELIST)
    val plan = UiGuard.plan(req("app" to "whatsapp", "op" to "back"), wa)
    assertEquals(listOf("com.whatsapp", "com.whatsapp.w4b"), plan.pkgs)
    assertEquals("app_not_allowed", codeOf { UiGuard.plan(req("app" to "com.spotify.music", "op" to "back"), wa) })
    assertEquals("app_not_allowed", codeOf { UiGuard.plan(req("app" to "telegram", "op" to "back"), wa) })
  }

  @Test fun liste_vide_ne_laisse_rien_passer() {
    assertEquals("app_not_allowed", codeOf { UiGuard.plan(req("app" to "whatsapp", "op" to "home"), emptyList()) })
  }

  @Test fun alias_ne_designe_que_des_paquets_de_la_liste_blanche() {
    val plan = UiGuard.plan(req("app" to "Telegram", "op" to "scroll"), listOf("org.telegram.messenger"))
    assertEquals(listOf("org.telegram.messenger"), plan.pkgs)
    assertEquals("bad_params", codeOf { UiGuard.plan(req("app" to "inconnu", "op" to "back"), wa) })
    assertEquals("bad_params", codeOf { UiGuard.plan(req("app" to "com..bad", "op" to "back"), wa) })
  }

  // ─── Forme des demandes ─────────────────────────────────────────────────

  @Test fun operations_valides() {
    assertEquals("Envoyer", UiGuard.plan(req("app" to "whatsapp", "op" to "tap", "text" to "Envoyer"), wa).text)
    assertEquals("Retour", UiGuard.plan(req("app" to "whatsapp", "op" to "tap", "desc" to "Retour", "index" to 1), wa).desc)
    val t = UiGuard.plan(req("app" to "whatsapp", "op" to "type", "text" to "J'arrive", "replace" to true), wa)
    assertEquals("J'arrive", t.text)
    assertTrue(t.replace)
    assertEquals("down", UiGuard.plan(req("app" to "whatsapp", "op" to "scroll"), wa).direction)
    assertEquals("up", UiGuard.plan(req("app" to "whatsapp", "op" to "scroll", "direction" to "up"), wa).direction)
    assertEquals(UiGuard.WAIT_DEFAULT_S, UiGuard.plan(req("app" to "whatsapp", "op" to "wait_text", "text" to "Camille"), wa).waitS)
    assertEquals(20, UiGuard.plan(req("app" to "whatsapp", "op" to "wait_text", "text" to "Camille", "wait_s" to 20), wa).waitS)
  }

  @Test fun operations_mal_formees() {
    fun bad(vararg kv: Pair<String, Any>) = assertEquals("bad_params", codeOf { UiGuard.plan(req("app" to "whatsapp", *kv), wa) })
    bad("op" to "swipe")
    bad("op" to "tap")
    bad("op" to "tap", "text" to "   ")
    bad("op" to "tap", "text" to "x", "index" to 99)
    bad("op" to "tap", "text" to "x".repeat(UiGuard.LABEL_MAX + 1))
    bad("op" to "type")
    bad("op" to "type", "text" to "x".repeat(UiGuard.TEXT_MAX + 1))
    bad("op" to "scroll", "direction" to "left")
    bad("op" to "wait_text")
    bad("op" to "wait_text", "text" to "x", "wait_s" to 21)
    bad("op" to "wait_text", "text" to "x", "wait_s" to 0)
  }

  @Test fun toucher_et_saisir_exigent_toujours_la_confirmation() {
    assertTrue(UiGuard.needsConfirm("ui_act", req("op" to "tap")))
    assertTrue(UiGuard.needsConfirm("ui_act", req("op" to "type")))
    for (op in listOf("scroll", "back", "home", "wait_text")) assertFalse(op, UiGuard.needsConfirm("ui_act", req("op" to op)))
    assertFalse(UiGuard.needsConfirm("flashlight", req("op" to "tap")))  // ne concerne que ui_act
  }

  // ─── Correspondance des libelles ────────────────────────────────────────

  @Test fun exact_prefere_a_contient() {
    val c = listOf(listOf("Envoyer un fichier", ""), listOf("Envoyer", ""), listOf("", "envoyer  "))
    assertEquals(listOf(1, 2), UiMatch.pick(c, " ENVOYER "))
  }

  @Test fun contient_seulement_sans_exact() {
    val c = listOf(listOf("Nouvelle discussion", ""), listOf("Appel", "Passer un appel vocal"))
    assertEquals(listOf(0), UiMatch.pick(c, "discussion"))
    assertEquals(listOf(1), UiMatch.pick(c, "appel vocal"))
    assertEquals(emptyList<Int>(), UiMatch.pick(c, "introuvable"))
    assertEquals(emptyList<Int>(), UiMatch.pick(c, "   "))
  }
}
