package dev.aura.mobile.device

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeLogicTest {
  // ─── R1 : 4401 ──────────────────────────────────────────────────────────

  @Test fun rejected_seulement_au_3e_4401_d_affilee() {
    val c = BadTokenCounter()
    assertFalse(c.onClose(4401))
    assertFalse(c.onClose(4401))
    assertTrue(c.onClose(4401))
  }

  @Test fun welcome_remet_le_compteur_a_zero() {
    val c = BadTokenCounter()
    c.onClose(4401); c.onClose(4401)
    c.reset()  // welcome
    assertFalse(c.onClose(4401))
    assertEquals(1, c.count)
  }

  @Test fun autres_codes_ne_comptent_pas() {
    val c = BadTokenCounter()
    assertFalse(c.onClose(1006))
    assertFalse(c.onClose(-1))
    assertEquals(0, c.count)
  }

  // ─── R2 : rattrapage `recent` ───────────────────────────────────────────

  private fun n(id: String, ts: Double, extra: String = "") =
    JSONObject("""{"id":"$id","ts":$ts,"text":"notif $id","category":"aura","severity":1$extra}""")

  @Test fun premiere_connexion_memorise_le_ts_max_sans_rien_afficher() {
    val r = NotifCatchUp.select(JSONArray().put(n("a", 100.0)).put(n("b", 200.0)), null, emptySet(), 300.0)
    assertTrue(r.show.isEmpty())
    assertEquals(200.0, r.lastTs, 0.0)
  }

  @Test fun premiere_connexion_historique_vide() {
    val r = NotifCatchUp.select(JSONArray(), null, emptySet(), 300.0)
    assertEquals(300.0, r.lastTs, 0.0)
  }

  @Test fun filtre_lu_archive_quiet_deja_vu_et_ancien() {
    val recent = JSONArray()
      .put(n("vieux", 90.0))
      .put(n("egal", 100.0))
      .put(n("lu", 110.0, ""","read":true"""))
      .put(n("archive", 120.0, ""","archived":true,"read":true"""))
      .put(n("muet", 130.0, ""","quiet":true"""))
      .put(n("affiche", 140.0))
      .put(n("ok", 150.0))
    val r = NotifCatchUp.select(recent, 100.0, setOf("affiche"), 160.0)
    assertEquals(listOf("ok"), r.show.map { it.getString("id") })
    assertEquals(150.0, r.lastTs, 0.0)
  }

  @Test fun huit_au_plus_les_plus_recentes_dans_l_ordre() {
    val recent = JSONArray()
    for (i in 1..12) recent.put(n("n$i", i * 10.0))
    val r = NotifCatchUp.select(recent, 0.0, emptySet(), 200.0)
    assertEquals((5..12).map { "n$it" }, r.show.map { it.getString("id") })
    assertEquals(120.0, r.lastTs, 0.0)
  }

  @Test fun sans_son_au_dela_de_30_min() {
    val now = 10_000.0
    val recent = JSONArray().put(n("vieille", now - 1801)).put(n("fraiche", now - 60))
    val r = NotifCatchUp.select(recent, 0.0, emptySet(), now)
    assertEquals(listOf(true, false), r.silent)
  }

  @Test fun last_ts_ne_recule_jamais() {
    val r = NotifCatchUp.select(JSONArray().put(n("a", 50.0)), 100.0, emptySet(), 200.0)
    assertTrue(r.show.isEmpty())
    assertEquals(100.0, r.lastTs, 0.0)
  }

  // ─── F1 : numero a rappeler ─────────────────────────────────────────────

  @Test fun numero_de_la_ligne_telephone() {
    assertEquals("0612345678", callNumber("📞 Appel manqué à 14:05 — Dupont SARL\n📱 06 12 34 56 78\n🏢 fiche"))
  }

  @Test fun numero_en_fin_de_premiere_ligne() {
    assertEquals("+33612345678", callNumber("📞 Appel manqué à 09:12 — +33 6 12 34 56 78"))
    assertEquals("0450123456", callNumber("📞 Appel manqué à 09:12 — 04.50.12.34.56"))
  }

  @Test fun pas_de_numero() {
    assertNull(callNumber("📞 Appel manqué à 09:12 — numéro masqué"))
    assertNull(callNumber("📞 Appel manqué à 09:12 — Dupont SARL"))
    assertNull(callNumber("☎️ Appel terminé avec Dupont (3 min)\n📝 devis"))
    assertNull(callNumber(""))
  }

  // ─── B : onglet neuf demande par la montre ──────────────────────────────

  @Test fun messages_retenus_puis_envoyes_dans_l_onglet_neuf() {
    val w = NewConvWait<String>()
    assertFalse("rien en attente : envoi direct", w.hold("avant", 0))
    w.start("r1", "montre", 1000)
    assertTrue(w.hold("m1", 2000))
    assertTrue(w.hold("m2", 3000))
    assertNull("req d'un autre (PWA, relais)", w.resolve("autre", 4000))
    assertTrue(w.matches("r1", 4000))
    val r = w.resolve("r1", 4000)!!
    assertEquals("montre", r.node)
    assertEquals(listOf("m1", "m2"), r.held)
    assertFalse(w.hold("apres", 5000))
    assertNull(w.expire(100_000))
  }

  @Test fun expiration_15s_rend_les_messages_sans_conv() {
    val w = NewConvWait<String>()
    w.start("r1", "montre", 0)
    assertTrue(w.hold("m1", 14_999))
    assertNull("pas encore", w.expire(14_999))
    assertFalse("delai ecoule : plus retenu", w.hold("m2", 15_000))
    assertEquals(listOf("m1"), w.expire(15_000))
    assertNull("resolu trop tard : ignore", w.resolve("r1", 15_001))
  }

  @Test fun annulation_hors_ligne() {
    val w = NewConvWait<String>()
    w.start("r1", "montre", 0)
    w.hold("m1", 10)
    assertEquals(listOf("m1"), w.cancel())
    assertFalse(w.waiting(20))
  }

  @Test fun deuxieme_demande_remplace_la_premiere() {
    val w = NewConvWait<String>()
    w.start("r1", "montre", 0)
    w.hold("m1", 10)
    w.start("r2", "montre", 5_000)
    assertNull(w.resolve("r1", 6_000))
    assertEquals(listOf("m1"), w.resolve("r2", 6_000)!!.held)
  }

  @Test fun coalescence_une_fois_par_seconde() {
    assertEquals(0L, coalesceDelay(0, 5_000, 1_000))
    assertEquals(400L, coalesceDelay(5_000, 5_600, 1_000))
    assertEquals(0L, coalesceDelay(5_000, 6_000, 1_000))
  }
  // ─── 01/10 soir : bridge sans les nouveaux messages, notifications deja portees par une carte ───────────

  @Test fun bad_type_cite_le_message_refuse() {
    val names = BridgeLogic.FLUX_CLIENT_TYPES
    assertEquals("usage_report", BridgeLogic.citedType("type de message inconnu : usage_report", names))
    assertEquals("missed_list", BridgeLogic.citedType("type inconnu « missed_list »", names))
    assertEquals("time_list", BridgeLogic.citedType("bad type: time_list", names))
    assertNull(BridgeLogic.citedType("type inconnu : phone_event", names))
    assertNull(BridgeLogic.citedType("", names))
  }

  @Test fun le_recap_d_appels_et_les_temps_sont_portes_par_leur_carte() {
    assertTrue(BridgeLogic.coveredByCard("aura-appel", "proactive:appel"))
    assertTrue(BridgeLogic.coveredByCard("aura-temps", "proactive:temps"))
  }

  @Test fun les_autres_notifications_restent_affichees() {
    // la fiche apres appel (apres_appel.py) est une aura-appel d'une autre origine : son bouton « Rappeler » reste
    assertFalse(BridgeLogic.coveredByCard("aura-appel", "apres_appel"))
    assertFalse(BridgeLogic.coveredByCard("aura-appel", null))
    assertFalse(BridgeLogic.coveredByCard("aura-fin", "proactive:appel"))
    assertFalse(BridgeLogic.coveredByCard("aura-conseil", "proactive:temps"))
    assertFalse(BridgeLogic.coveredByCard("notifications", "batch:lot"))
  }
}
