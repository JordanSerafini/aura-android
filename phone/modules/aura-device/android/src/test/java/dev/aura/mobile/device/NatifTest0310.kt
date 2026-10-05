package dev.aura.mobile.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Ajouts natifs du 03/10 (PROTOCOL.md §19) : notification retiree, temps d'ecran, veille de reconnexion, compteurs. */
class NatifTest0310 {
  // ─── notif_removed ──────────────────────────────────────────────────────

  @Test fun raisons_android_traduites_et_mecanique_ecartee() {
    assertEquals("click", NotifRemoval.reason(1))
    assertEquals("cancel", NotifRemoval.reason(2))
    assertEquals("cancel_all", NotifRemoval.reason(3))
    assertEquals("app_cancel", NotifRemoval.reason(8))
    assertEquals("listener_cancel", NotifRemoval.reason(10))
    assertEquals("timeout", NotifRemoval.reason(19))
    // resume de groupe annule, regroupement automatique, paquet modifie : rien ne part
    for (code in listOf(4, 5, 12, 13, 16, 21, 99)) assertNull("raison $code", NotifRemoval.reason(code))
  }

  @Test fun champs_sans_contenu_et_cle_jamais_en_clair() {
    val key = "0|com.whatsapp|1|33612345678@s.whatsapp.net|10150"
    val f = NotifRemoval.fields("com.whatsapp", "WhatsApp", 2, "msg", "individual_chat_defaults", true,
      postTime = 1_000_000L, key = key, now = 1_090_000L)
    assertNotNull(f)
    f!!
    assertEquals("cancel", f.getString("reason"))
    assertEquals("msg", f.getString("category"))
    assertEquals("individual_chat_defaults", f.getString("channel"))
    assertEquals(90L, f.getLong("age_s"))
    assertTrue(f.getBoolean("clearable"))
    assertEquals(12, f.getString("nid").length)
    assertFalse("le numero de la cle ne sort jamais", f.toString().contains("33612345678"))
    assertFalse(f.has("title") || f.has("text"))
  }

  @Test fun canal_qui_porte_un_numero_ou_une_phrase_n_est_pas_envoye() {
    assertNull(NotifRemoval.channel("conv_33612345678"))
    assertNull(NotifRemoval.channel("Messages de Camille"))
    assertEquals("promo-2", NotifRemoval.channel("promo-2"))
    assertNull(NotifRemoval.category("Promo!"))
    assertEquals("promo", NotifRemoval.category("promo"))
  }

  @Test fun raison_mecanique_rien_a_envoyer() {
    assertNull(NotifRemoval.fields("com.x.y", "X", 12, null, null, true, 1L, "k", 2L))
  }

  @Test fun notif_removed_hors_limite_globale() {
    val g = EventGate()
    // 20 retraits en rafale (« Tout effacer ») : ils passent, et la sonnerie qui suit aussi
    for (i in 0 until 20) assertTrue("retrait $i", g.allow("notif_removed", "n$i", i.toLong()))
    assertFalse(g.allow("notif_removed", "n21", 30L))              // 20 par minute au plus
    assertTrue(g.allow("notif_removed", "n22", 61_000L))
    // la limite globale (40 / 10 min) n'a pas ete entamee par ces retraits
    for (i in 0 until 5) assertTrue(g.allow("call_ringing", "06$i", 62_000L + i))
  }

  @Test fun reglage_notif_removed_actif_par_defaut_et_desactivable() {
    assertTrue(EventSettings().allows("notif_removed"))
    assertFalse(EventSettings.fromJson("""{"notif_removed": false}""").allows("notif_removed"))
    assertTrue(EventSettings.fromJson("""{}""").allows("notif_removed"))  // reglages d'avant le 03/10
    assertFalse(EventSettings.fromJson("""{"enabled": false}""").allows("notif_removed"))
  }

  // ─── app_usage ──────────────────────────────────────────────────────────

  private fun ev(pkg: String, type: Int, ts: Long, cls: String = "Main") = AppUsageLogic.Ev(pkg, cls, type, ts)

  @Test fun temps_au_premier_plan_par_app() {
    val R = AppUsageLogic.RESUMED
    val P = AppUsageLogic.PAUSED
    val events = listOf(
      ev("com.whatsapp", R, 1_000), ev("com.whatsapp", P, 61_000),            // 60 s
      ev("com.whatsapp", R, 100_000), ev("com.whatsapp", P, 160_000),         // + 60 s
      ev("com.google.maps", R, 200_000),                                       // encore ouverte a la fin
    )
    val ms = AppUsageLogic.foreground(events, 0, 260_000)
    assertEquals(120_000L, ms["com.whatsapp"])
    assertEquals(60_000L, ms["com.google.maps"])
  }

  @Test fun app_deja_ouverte_au_debut_de_la_fenetre_et_ecran_eteint() {
    val ms = AppUsageLogic.foreground(listOf(
      ev("com.a.b", AppUsageLogic.PAUSED, 50_000),                             // ouverte avant begin=20 000
      ev("com.c.d", AppUsageLogic.RESUMED, 60_000), ev("com.c.d", AppUsageLogic.SCREEN_OFF, 90_000),
      ev("com.c.d", AppUsageLogic.PAUSED, 95_000),                             // pause apres l'extinction : rien de plus
    ), 20_000, 200_000)
    assertEquals(30_000L, ms["com.a.b"])
    assertEquals(30_000L, ms["com.c.d"])
  }

  @Test fun deux_activites_d_une_app_comptent_une_fois() {
    val ms = AppUsageLogic.foreground(listOf(
      ev("com.a.b", AppUsageLogic.RESUMED, 0, "A"), ev("com.a.b", AppUsageLogic.RESUMED, 10_000, "B"),
      ev("com.a.b", AppUsageLogic.PAUSED, 20_000, "A"), ev("com.a.b", AppUsageLogic.PAUSED, 70_000, "B"),
    ), 0, 100_000)
    assertEquals(70_000L, ms["com.a.b"])
  }

  @Test fun classement_sans_lanceur_ni_moins_d_une_minute() {
    val top = AppUsageLogic.ranked(mapOf("launcher" to 900_000L, "a" to 300_000L, "b" to 600_000L, "c" to 59_000L),
      setOf("launcher"), 10)
    assertEquals(listOf("b", "a"), top.map { it.first })
  }

  @Test fun fenetres_24h_aujourd_hui_7_jours() {
    val zone = ZoneId.of("Europe/Paris")
    val now = java.time.ZonedDateTime.of(2026, 10, 3, 14, 30, 0, 0, zone).toInstant().toEpochMilli()
    assertEquals(now - 86_400_000L, AppUsageLogic.begin("24h", now, zone))
    assertEquals(now - 7 * 86_400_000L, AppUsageLogic.begin("7d", now, zone))
    assertEquals(now - (14 * 60 + 30) * 60_000L, AppUsageLogic.begin("today", now, zone))
  }

  // ─── veille de reconnexion ──────────────────────────────────────────────

  @Test fun backoff_hors_ligne_plafonne_a_30_min() {
    assertEquals(60_000L, WatchdogLogic.offlineDelay(0))
    assertEquals(120_000L, WatchdogLogic.offlineDelay(1))
    assertEquals(1_800_000L, WatchdogLogic.offlineDelay(5))
    assertEquals(1_800_000L, WatchdogLogic.offlineDelay(50))
    assertEquals(60_000L, WatchdogLogic.offlineDelay(-1))
  }

  @Test fun socket_muette_seulement_sans_aucun_message_apres_le_ping() {
    assertFalse(WatchdogLogic.dead(pingAt = 1_000, lastRx = 1_500, now = 30_000))   // pong (ou autre) recu
    assertFalse(WatchdogLogic.dead(pingAt = 1_000, lastRx = 0, now = 10_000))       // trop tot pour juger
    assertTrue(WatchdogLogic.dead(pingAt = 1_000, lastRx = 900, now = 21_000))
  }

  // ─── compteurs d'usage ──────────────────────────────────────────────────

  @Test fun au_plus_cinq_rapports_par_passage() {
    val b = UsageBuckets()
    val today = java.time.LocalDate.of(2026, 10, 3)
    for (d in 0 until 8) UsageLogic.add(b, today.minusDays(d.toLong()).toString(), "ecran.aura")
    val all = UsageLogic.reports(b, "s22-natif", today)
    assertEquals(8, all.size)
    assertTrue(UsageLogic.MAX_REPORTS_PER_PASS <= 5)  // le bridge en refuse au-dela de 6 par minute
    assertEquals(today.minusDays(7).toString(), all.take(UsageLogic.MAX_REPORTS_PER_PASS).first().day)  // les plus anciens d'abord
  }
}
