package dev.aura.mobile.device

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.aura.mobile.device.RingTracker.Decision.EMIT
import dev.aura.mobile.device.RingTracker.Decision.NONE
import dev.aura.mobile.device.RingTracker.Decision.WAIT

class CallLogicTest {
  // ─── Sonnerie ───────────────────────────────────────────────────────────

  @Test fun deux_diffusions_dont_la_seconde_porte_le_numero_donnent_une_seule_emission() {
    val t = RingTracker()
    assertEquals(WAIT, t.onState("RINGING", null))            // 1re diffusion : sans numero
    assertEquals(EMIT, t.onState("RINGING", "+33612345678"))  // 2e : avec
    assertEquals("+33612345678", t.number)
    assertEquals(NONE, t.onState("RINGING", "+33612345678"))  // pas de 3e emission
    assertEquals(NONE, t.onGraceElapsed())                    // le delai de grace n'emet plus rien
  }

  @Test fun numero_masque_emet_apres_le_delai_de_grace() {
    val t = RingTracker()
    assertEquals(WAIT, t.onState("RINGING", null))
    assertEquals(NONE, t.onState("RINGING", ""))              // diffusion sans numero (vide) : toujours en attente
    assertEquals(EMIT, t.onGraceElapsed())
    assertNull(t.number)
    assertEquals(NONE, t.onGraceElapsed())
  }

  @Test fun numero_present_des_la_premiere_diffusion_emet_tout_de_suite() {
    val t = RingTracker()
    assertEquals(EMIT, t.onState("RINGING", " 0612345678 "))
    assertEquals("0612345678", t.number)
  }

  @Test fun decrocher_ou_raccrocher_remet_a_zero() {
    val t = RingTracker()
    t.onState("RINGING", null)
    assertEquals(NONE, t.onState("OFFHOOK", null))
    assertEquals(NONE, t.onGraceElapsed())                    // plus de sonnerie : rien a emettre en retard
    assertEquals(NONE, t.onState("IDLE", null))
    assertEquals(EMIT, t.onState("RINGING", "0699999999"))    // l'appel suivant emet de nouveau
    assertEquals("0699999999", t.number)
    t.onState("IDLE", null)
    assertNull(t.number)
  }

  @Test fun etat_inconnu_ou_absent_ne_declenche_rien() {
    val t = RingTracker()
    assertEquals(NONE, t.onState(null, "0612"))
    assertEquals(NONE, t.onState("bidon", "0612"))
  }

  // ─── Carte d'appel ──────────────────────────────────────────────────────

  private fun card(json: String) = CallCardLogic.parse(JSONObject(json))

  @Test fun carte_complete() {
    val c = card("""{"type":"call_card","number":"+33 6 12 34 56 78","title":"Dupont SA","lines":["Ticket #123 ouvert","Dernier appel hier","Promesse : rappeler"],"ts":1790000000}""")!!
    assertEquals("Dupont SA", c.title)
    assertEquals(3, c.lines.size)
    assertEquals("+33 6 12 34 56 78", c.number)
    assertEquals("Ticket #123 ouvert\nDernier appel hier\nPromesse : rappeler", CallCardLogic.body(c))
  }

  @Test fun fiche_partielle_est_lue_et_dite() {
    val c = card("""{"title":"ACME","lines":["🏢 ACME Industrie (Lyon)","🤝 promis : rappeler"],"ts":1790000000.5,"partial":true}""")!!
    assertTrue(c.partial)
    assertTrue("la mention est dans le corps", CallCardLogic.body(c).endsWith(CallCardLogic.PARTIAL_NOTE))
    assertEquals("Fiche partielle · 🏢 ACME Industrie (Lyon)", CallCardLogic.summary(c))
    assertTrue(CallCardLogic.toWatch(c).getBoolean("partial"))
    // sans `partial` : rien ne change
    val full = card("""{"title":"ACME","lines":["🏢 ACME Industrie (Lyon)"]}""")!!
    assertFalse(full.partial)
    assertEquals("🏢 ACME Industrie (Lyon)", CallCardLogic.body(full))
    assertEquals("🏢 ACME Industrie (Lyon)", CallCardLogic.summary(full))
    assertFalse(CallCardLogic.toWatch(full).has("partial"))
  }

  @Test fun partial_n_est_vrai_que_pour_un_vrai_booleen() {
    assertFalse(card("""{"title":"X","partial":false}""")!!.partial)
    assertFalse(card("""{"title":"X","partial":null}""")!!.partial)
    assertFalse(card("""{"title":"X","partial":"oui"}""")!!.partial)
    assertFalse(card("""{"title":"X","partial":1}""")!!.partial)
    assertTrue("titre seul, partielle : la mention suffit a ne pas laisser croire a une fiche complete",
      CallCardLogic.body(card("""{"title":"X","partial":true}""")!!).contains("partielle"))
  }

  @Test fun titre_absent_donne_numero_connu() {
    assertEquals("Numéro connu", card("""{"lines":["Ticket ouvert"]}""")!!.title)
    assertEquals("Numéro connu", card("""{"title":"  ","lines":["x"]}""")!!.title)
    assertEquals("Numéro connu", card("""{"title":null,"lines":["x"]}""")!!.title)
  }

  @Test fun cinq_lignes_au_plus_et_lignes_tronquees() {
    val c = card(JSONObject().put("lines", JSONArray(List(9) { "ligne $it" })).toString())!!
    assertEquals(5, c.lines.size)
    val long = card(JSONObject().put("title", "t").put("lines", JSONArray(listOf("a".repeat(500)))).toString())!!
    assertEquals(CallCardLogic.LINE_MAX, long.lines[0].length)
    assertTrue(long.lines[0].endsWith("…"))
  }

  @Test fun texte_seul_controles_et_marques_bidirectionnelles_retires() {
    val c = card(JSONObject().put("title", "Dupont‮txt.exe\u0000").put("lines", JSONArray(listOf("a\nb\r\tc", "‏⁦caché⁩"))).toString())!!
    assertFalse(c.title.contains('‮'))
    assertFalse(c.title.contains('\u0000'))
    assertEquals("a b c", c.lines[0])
    assertEquals("caché", c.lines[1])
  }

  @Test fun une_ligne_qui_n_est_pas_du_texte_est_ignoree() {
    val c = card("""{"title":"X","lines":["ok",{"url":"http://evil"},42,null,"deux"]}""")!!
    assertEquals(listOf("ok", "deux"), c.lines)
  }

  @Test fun un_lien_reste_du_texte_et_le_numero_douteux_est_jete() {
    val c = card("""{"title":"X","number":"http://evil.example/x","lines":["https://evil.example/a?b=c"]}""")!!
    assertNull("pas un numero : jamais affiche", c.number)
    assertEquals("https://evil.example/a?b=c", c.lines[0])  // texte brut, la notification ne le rend pas cliquable
  }

  @Test fun carte_vide_n_affiche_rien() {
    assertNull(card("""{"type":"call_card"}"""))
    assertNull(card("""{"lines":[]}"""))
    assertNull(card("""{"lines":["  ","\n"]}"""))
    assertNotNull(card("""{"title":"Dupont SA"}"""))  // un titre seul suffit
  }

  @Test fun fraicheur_de_la_carte() {
    val now = 1_790_000_000L
    assertTrue(CallCardLogic.fresh(0L, now))                       // pas de ts : recente
    assertTrue(CallCardLogic.fresh(now - 60, now))
    assertTrue(CallCardLogic.fresh((now - 60) * 1000, now))        // millisecondes tolerees
    assertFalse(CallCardLogic.fresh(now - 181, now))               // l'appel est fini
    assertFalse(CallCardLogic.fresh(now + 3600, now))              // dans le futur : suspect
  }

  @Test fun forme_envoyee_a_la_montre() {
    val w = CallCardLogic.toWatch(card("""{"title":"Dupont","number":"0612345678","lines":["a","b"],"ts":5}""")!!)
    assertEquals("Dupont", w.getString("title"))
    assertEquals(2, w.getJSONArray("lines").length())
    assertEquals("0612345678", w.getString("number"))
    assertFalse(CallCardLogic.toWatch(card("""{"title":"Dupont","lines":["a"]}""")!!).has("number"))
  }

  // ─── Reglage et garde-fous du declencheur ───────────────────────────────

  @Test fun call_ringing_actif_par_defaut_et_desactivable() {
    val s = EventSettings.fromJson(null)
    assertTrue(s.allows("call_ringing"))
    assertFalse(EventSettings.fromJson("""{"call_ringing":false}""").allows("call_ringing"))
    assertFalse(EventSettings.fromJson("""{"enabled":false}""").allows("call_ringing"))  // l'interrupteur general le coupe aussi
    assertTrue(EventSettings.fromJson(s.toJson().toString()).callRinging)
    assertFalse(EventSettings.fromJson(EventSettings(callRinging = false).toJson().toString()).callRinging)
  }

  @Test fun call_ringing_suit_l_anti_spam_comme_missed_call() {
    val g = EventGate()
    assertTrue(g.allow("call_ringing", "0612", 0))
    assertFalse(g.allow("call_ringing", "0612", 30_000))     // meme numero dans la minute : doublon
    assertTrue(g.allow("call_ringing", "0613", 30_000))      // autre numero : passe
    assertTrue(g.allow("call_ringing", "0612", 61_000))
    for (i in 0 until 5) g.allow("call_ringing", "n$i", 100_000L + i)
    assertFalse("5 au plus par 10 min", g.allow("call_ringing", "n9", 101_000))
  }
}
