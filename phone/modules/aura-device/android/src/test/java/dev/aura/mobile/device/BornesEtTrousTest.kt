package dev.aura.mobile.device

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Revue du 24/10 (points 2 a 4) : bornes exactes, NaN, depassement d'entier, texte de tiers, Relay, ChunkCollector. */
class BornesEtTrousTest {
  private fun ctx(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }
  private fun props(vararg minutes: Any) = TimeLogic.parseProposals(JSONObject().put("items", JSONArray().apply {
    minutes.forEachIndexed { i, m -> put(JSONObject().put("id", "2026-10-0${i + 1}-T$i").put("minutes", m)) }
  }))

  // ─── UsageLogic : plafond de 5 rapports par passage ─────────────────────
  @Test fun un_passage_n_envoie_que_5_jours_les_plus_anciens_d_abord() {
    val today = LocalDate.of(2026, 10, 20)
    val b = UsageBuckets()
    for (d in 1..8) UsageLogic.add(b, "2026-10-%02d".format(d), "ecran.actions")
    val all = UsageLogic.reports(b, "s22", today)
    assertEquals(8, all.size)
    val batch = UsageLogic.batch(all)
    assertEquals(5, UsageLogic.MAX_REPORTS_PER_PASS)
    assertEquals(5, batch.size)
    assertEquals((1..5).map { "2026-10-%02d".format(it) }, batch.map { it.day })
    assertEquals(3, UsageLogic.batch(all.take(3)).size)  // moins de 5 : tout part
  }

  @Test fun bornes_exactes_de_l_usage() {
    val b = UsageBuckets()
    assertTrue(UsageLogic.add(b, "d", "x".repeat(40)))
    assertFalse(UsageLogic.add(b, "d", "x".repeat(41)))
    assertFalse(UsageLogic.add(b, "d", "a", -1))
    assertTrue(UsageLogic.add(b, "d", "a", UsageLogic.MAX_VALUE))
    assertTrue(UsageLogic.add(b, "d", "a", Int.MAX_VALUE))  // pas de debordement : plafonne
    assertEquals(UsageLogic.MAX_VALUE, b["d"]!!["a"])
    // fenetre du bridge : -89 jours accepte, -90 refuse ; demain accepte, apres-demain refuse
    val today = LocalDate.of(2026, 10, 1)
    val w = UsageBuckets()
    for (d in listOf(today.minusDays(89), today.minusDays(90), today.plusDays(1), today.plusDays(2))) UsageLogic.add(w, d.toString(), "a")
    assertEquals(listOf(today.minusDays(89).toString(), today.plusDays(1).toString()), UsageLogic.reports(w, "s22", today).map { it.day })
    // elagage : 14 jours gardes, le 15e part
    val p = UsageBuckets()
    UsageLogic.add(p, today.minusDays(14).toString(), "a"); UsageLogic.add(p, today.minusDays(15).toString(), "a")
    assertEquals(1, UsageLogic.prune(p, today))
    assertEquals(listOf(today.minusDays(14).toString()), p.keys.toList())
  }

  // ─── PhoneContext (ContextLogic) ────────────────────────────────────────
  @Test fun le_fuseau_horaire_est_un_changement_notable() {
    assertEquals("tz", ContextLogic.notable(ctx("tz" to "Europe/Paris"), ctx("tz" to "America/New_York")))
    assertNull(ContextLogic.notable(ctx("tz" to "Europe/Paris"), ctx("tz" to "Europe/Paris")))
    assertEquals("tz", ContextLogic.notable(ctx(), ctx("tz" to "America/New_York")))  // 1re valeur connue
  }

  @Test fun bornes_exactes_de_batterie_et_de_deplacement() {
    assertNull(ContextLogic.notable(ctx("battery" to 70), ctx("battery" to 79)))      // meme dizaine
    assertEquals("battery", ContextLogic.notable(ctx("battery" to 79), ctx("battery" to 80)))
    assertNull(ContextLogic.notable(ctx("battery" to 15), ctx("battery" to 19)))      // 15 n'est pas « sous 15 »
    assertNull(ContextLogic.notable(ctx("battery" to 16), ctx("battery" to 15)))      // meme dizaine, pas encore sous 15
    assertEquals("battery", ContextLogic.notable(ctx("battery" to 15), ctx("battery" to 14)))  // passe sous 15
    assertEquals("battery", ContextLogic.notable(ctx("battery" to 14), ctx("battery" to 13)))
    // deplacement : > 300 m strictement
    val a = JSONObject().put("lat", 45.0).put("lon", 5.0)
    val dLat = Math.toDegrees(300.0 / 6_371_000.0)
    val at300 = JSONObject().put("lat", 45.0 + dLat * 0.999).put("lon", 5.0)
    val at301 = JSONObject().put("lat", 45.0 + dLat * 1.01).put("lon", 5.0)
    assertNull(ContextLogic.notable(ctx("location" to a), ctx("location" to at300)))
    assertEquals("location", ContextLogic.notable(ctx("location" to a), ctx("location" to at301)))
    assertEquals(0.0, ContextLogic.meters(45.0, 5.0, 45.0, 5.0), 0.0)
  }

  @Test fun une_zone_sans_coordonnees_ne_fait_pas_planter_la_fusion() {
    // optDouble rend NaN pour une latitude absente ; JSONObject.put(NaN) leve : la fusion entiere echouait
    val zones = JSONArray()
      .put(JSONObject().put("name", "Cassee").put("lon", 2.0))
      .put(JSONObject().put("name", "Bonne").put("lat", 1.0).put("lon", 2.0))
      .put("pas un objet")
    val out = ContextLogic.mergeZone(zones, "Maison", 5.0, 6.0, 150.0)
    assertEquals(listOf("Bonne", "Maison"), (0 until out.length()).map { out.getJSONObject(it).getString("name") })
  }

  @Test fun dix_zones_au_plus_la_plus_ancienne_part() {
    val zones = JSONArray().apply { for (i in 1..10) put(JSONObject().put("name", "z$i").put("lat", i.toDouble()).put("lon", 0.0)) }
    val out = ContextLogic.mergeZone(zones, "neuve", 0.0, 0.0, 100.0)
    assertEquals(10, out.length())
    assertEquals("z2", out.getJSONObject(0).getString("name"))
    assertEquals("neuve", out.getJSONObject(9).getString("name"))
    assertEquals(150.0, out.getJSONObject(0).getDouble("radius_m"), 0.0)  // rayon absent : 150 m
    assertEquals(10, ContextLogic.mergeZone(zones, " Z3 ", 0.0, 0.0, 100.0).length())  // remplace z3 (casse, espaces)
  }

  // ─── TimeLogic.parseProposals ───────────────────────────────────────────
  @Test fun minutes_aux_bornes_exactes() {
    assertEquals(listOf(1, 480), props(1, 480).map { it.minutes })
    assertTrue(props(0).isEmpty()); assertTrue(props(481).isEmpty()); assertTrue(props(-5).isEmpty())
    assertTrue(props("90").isEmpty())  // une chaine n'est pas un nombre
    assertTrue(props(0.5).isEmpty())
  }

  @Test fun une_duree_qui_deborde_un_entier_n_est_pas_acceptee() {
    // 4294967297 = 2^32 + 1 : Number.toInt() rendait 1 minute, donc une proposition « valide »
    assertTrue(props(4_294_967_297L).isEmpty())
    assertTrue(props(4_294_967_296L + 480).isEmpty())
    assertTrue(props(1e10).isEmpty())
    assertTrue(props(Double.MAX_VALUE).isEmpty())
    assertTrue(props(-4_294_967_295L).isEmpty())
  }

  @Test fun id_aux_bornes_exactes() {
    fun one(id: String) = TimeLogic.parseProposals(JSONObject().put("items", JSONArray().put(JSONObject().put("id", id).put("minutes", 10))))
    assertEquals(1, one("2026-10-01-" + "T".repeat(40)).size)
    assertTrue(one("2026-10-01-" + "T".repeat(41)).isEmpty())
    assertTrue(one("2026-10-01-").isEmpty())
    assertTrue(one("2026-10-01-T\n").isEmpty())
  }

  // ─── clipEventText ──────────────────────────────────────────────────────
  @Test fun coupe_a_la_borne_exacte() {
    assertEquals("abcde", clipEventText("abcde", 5))
    assertEquals("abcd…", clipEventText("abcdef", 5))
  }

  @Test fun une_coupe_ne_laisse_jamais_la_moitie_d_un_emoji() {
    val out = clipEventText("abc😀def", 5)  // le 4e caractere serait la 1re moitie du smiley
    assertTrue(out, out.indices.none { Character.isHighSurrogate(out[it]) && (it + 1 >= out.length || !Character.isLowSurrogate(out[it + 1])) })
    assertTrue(out.length <= 5)
    assertEquals("a😀", clipEventText("a😀", 3))  // tient : pas coupe
  }

  @Test fun marques_bidirectionnelles_retirees() {
    // U+202E (RLO) retourne l'affichage d'un nom d'expediteur ; U+200E/F, U+202A-E, U+2066-2069 : jamais remontes
    assertEquals("Paul manager", clipEventText("Paul ‮manager", 80))
    assertEquals("ab", clipEventText("a‎‏b", 80))
    assertEquals("ab", clipEventText("⁦a⁩b", 80))
    assertEquals("", clipEventText("‮‬", 80))
  }

  // ─── Relay : bridgeDown, prune ; ChunkCollector ─────────────────────────
  private fun voice(id: String) = JSONObject().put("type", "voice").put("id", id).put("audio", "AAAA").put("origin", "talk")

  @Test fun bridge_perdu_previent_chaque_demande_et_vide_la_table() {
    val io = RelayTest.FakeIo(); val r = Relay(io)
    val waiting = RelayTest.Sink(); val finished = RelayTest.Sink()
    r.submitNow(Relay.Req("w1", voice("w1"), waiting, speak = false))
    r.submitNow(Relay.Req("f1", voice("f1"), finished, speak = true))
    r.onBridge(JSONObject("""{"type":"done","id":"f1","text":"ok"}"""))
    assertTrue(r.active("w1")); assertTrue(r.active("f1"))
    r.bridgeDown()
    assertEquals("offline", waiting.states.last())
    assertEquals("Connexion au serveur perdue", finished.failed)  // reponse terminee, voix attendue : echec de la voix
    assertFalse(r.active("w1")); assertFalse(r.active("f1"))
    r.bridgeDown()  // rien a perdre : sans effet, sans exception
  }

  @Test fun une_demande_de_plus_de_30_min_est_oubliee_au_prochain_envoi() {
    val io = RelayTest.FakeIo(); val r = Relay(io)
    io.clock = System.currentTimeMillis()
    r.submitNow(Relay.Req("old", voice("old"), RelayTest.Sink()))
    io.clock += 29 * 60_000L
    r.submitNow(Relay.Req("mid", voice("mid"), RelayTest.Sink()))
    assertTrue("29 min : gardee", r.active("old"))
    io.clock += 2 * 60_000L  // 31 min apres la premiere
    r.submitNow(Relay.Req("new", voice("new"), RelayTest.Sink()))
    assertFalse("31 min : oubliee", r.active("old"))
    assertTrue(r.active("new"))
  }

  @Test fun morceaux_audio_remis_dans_l_ordre_des_seq() {
    val c = ChunkCollector()
    assertEquals(0, c.bytes().size)
    c.add(2, "C".toByteArray()); c.add(0, "A".toByteArray()); c.add(1, "B".toByteArray())
    assertEquals("ABC", String(c.bytes()))
    c.add(1, "b".toByteArray())  // un doublon de seq remplace
    assertEquals("AbC", String(c.bytes()))
  }
}
