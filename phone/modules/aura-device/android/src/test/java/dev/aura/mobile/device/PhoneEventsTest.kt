package dev.aura.mobile.device

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneEventsTest {
  // ─── Anti-spam ──────────────────────────────────────────────────────────

  @Test fun meme_evenement_dans_la_fenetre_est_ecarte() {
    val g = EventGate()
    assertTrue(g.allow("notification", "wa|Camille|salut", 0))
    assertFalse(g.allow("notification", "wa|Camille|salut", 119_000))  // fenetre 2 min
    assertTrue(g.allow("notification", "wa|Camille|salut", 121_000))
    assertTrue(g.allow("notification", "wa|Camille|autre", 121_500))   // autre contenu : passe
  }

  @Test fun limite_de_debit_par_kind() {
    val g = EventGate()
    for (i in 0 until 8) assertTrue("notif $i", g.allow("notification", "n$i", i * 1000L))
    assertFalse(g.allow("notification", "n9", 9_000))              // 9e en moins d'une minute
    assertTrue(g.allow("notification", "n10", 61_000))             // la fenetre a glisse
    assertTrue(g.allow("missed_call", "0612", 9_000))              // un autre kind n'est pas touche
  }

  @Test fun un_refus_ne_consomme_ni_quota_ni_fenetre() {
    val g = EventGate()
    assertTrue(g.allow("battery_low", "-", 0))
    repeat(20) { assertFalse(g.allow("battery_low", "-", 1_000L + it)) }  // doublons : rien de consomme
    assertTrue(g.allow("battery_low", "autre", 2_000))                    // 2e sur 2 par heure
    assertFalse(g.allow("battery_low", "encore", 3_000))                  // 3e : trop frequent
    assertFalse(g.allow("battery_low", "-", 1_800_001))                   // fenetre de doublon passee, quota horaire non
    assertTrue(g.allow("battery_low", "-", 3_600_001))                    // le premier est sorti de l'heure glissante
  }

  @Test fun limite_globale() {
    val g = EventGate(global = 5 to 600_000L)
    for (i in 0 until 5) assertTrue("c$i", g.allow("charger", "c$i", i * 100L))
    assertFalse(g.allow("charger", "c5", 600L))          // 6e, tous kinds confondus
    assertFalse(g.allow("zone", "z", 700L))              // un autre kind est bloque aussi
    assertTrue(g.allow("zone", "z", 600_100L))           // la fenetre globale a glisse
  }

  @Test fun kind_inconnu_refuse() {
    assertFalse(EventGate().allow("inconnu", "x", 0))
  }

  // ─── Batterie basse : une fois par descente ─────────────────────────────

  @Test fun batterie_une_seule_fois_par_descente() {
    val b = BatteryLatch()
    assertFalse(b.step(16, false))
    assertTrue(b.step(14, false))    // franchit 15 % : envoi
    assertFalse(b.step(13, false))
    assertFalse(b.step(9, false))
  }

  @Test fun batterie_rearmee_par_la_charge_ou_20_pourcent() {
    val b = BatteryLatch()
    assertTrue(b.step(10, false))
    assertFalse(b.step(11, true))     // branchee : rearme, jamais d'alerte en charge
    assertTrue(b.step(12, false))     // debranchee toujours basse : nouvelle descente
    assertFalse(b.step(17, false))    // entre 15 et 19 : rien ne change (pas de rearmement)
    assertFalse(b.step(14, false))
    assertFalse(b.step(21, false))    // >= 20 : rearme
    assertTrue(b.step(14, false))
  }

  @Test fun batterie_basse_en_charge_ne_previent_pas() {
    assertFalse(BatteryLatch().step(5, true))
  }

  // ─── Zones ──────────────────────────────────────────────────────────────

  private val maison = ZoneTracker.Zone("Maison", 45.8992, 6.1294, 150.0)
  private val now = 10_000_000L

  // ~111 m par 0,001 de latitude
  private fun at(dLat: Double, acc: Double = 20.0, fix: Long = now - 60_000L, prev: Set<String>?, zones: List<ZoneTracker.Zone> = listOf(maison)) =
    ZoneTracker.step(prev, 45.8992 + dLat, 6.1294, acc, fix, zones, now)

  @Test fun premiere_observation_apprend_sans_evenement() {
    val s = at(0.0, prev = null)!!
    assertEquals(setOf("Maison"), s.inside)
    assertTrue(s.events.isEmpty())
  }

  @Test fun entree_puis_sortie_avec_hysteresis() {
    val enter = at(0.0009, prev = emptySet())!!            // ~100 m : dedans
    assertEquals(listOf("enter" to "Maison"), enter.events)
    val edge = at(0.0015, prev = setOf("Maison"))!!        // ~167 m : hors rayon (150) mais sous 187,5 m
    assertTrue(edge.events.isEmpty())
    assertEquals(setOf("Maison"), edge.inside)
    val out = at(0.0025, prev = setOf("Maison"))!!         // ~278 m : sorti
    assertEquals(listOf("exit" to "Maison"), out.events)
    assertTrue(out.inside.isEmpty())
  }

  @Test fun entree_exige_le_vrai_rayon() {
    val s = at(0.0015, prev = emptySet())!!                // 167 m : pas assez pres pour ENTRER
    assertTrue(s.events.isEmpty())
    assertTrue(s.inside.isEmpty())
  }

  @Test fun position_vieille_ou_imprecise_ne_change_rien() {
    assertNull(at(0.0, fix = now - ZoneTracker.MAX_AGE_MS - 1, prev = emptySet()))
    assertNull(at(0.0, acc = 500.0, prev = emptySet()))
  }

  @Test fun zone_supprimee_disparait_sans_evenement() {
    val s = at(0.0, prev = setOf("Maison", "Ancienne"))!!
    assertEquals(setOf("Maison"), s.inside)
    assertTrue(s.events.isEmpty())
  }

  @Test fun lecture_des_zones_du_bridge() {
    val arr = JSONArray()
      .put(JSONObject().put("name", "Travail").put("lat", 1.5).put("lon", 2.5).put("radius_m", 200.0))
      .put(JSONObject().put("name", "  ").put("lat", 1.0).put("lon", 2.0))
      .put(JSONObject().put("name", "SansCoord"))
    val z = ZoneTracker.parseZones(arr)
    assertEquals(1, z.size)
    assertEquals("Travail", z[0].name)
    assertEquals(200.0, z[0].radiusM, 0.0)
    assertTrue(ZoneTracker.parseZones(null).isEmpty())
  }

  // ─── Reglages ───────────────────────────────────────────────────────────

  @Test fun reglages_par_defaut_tout_actif_mais_aucune_app() {
    val s = EventSettings.fromJson(null)
    assertTrue(s.enabled)
    for (k in listOf("missed_call", "battery_low", "charger", "zone", "notification")) assertTrue(k, s.allows(k))
    assertTrue(s.notifApps.isEmpty())
    assertFalse(s.allows("inconnu"))
  }

  @Test fun interrupteur_general_et_par_type() {
    val off = EventSettings.fromJson("""{"enabled":false}""")
    assertFalse(off.allows("missed_call"))
    val one = EventSettings.fromJson("""{"charger":false}""")
    assertFalse(one.allows("charger"))
    assertTrue(one.allows("zone"))
  }

  @Test fun apps_sensibles_jamais_retenues_pour_les_notifications() {
    val s = EventSettings.fromJson("""{"notif_apps":["com.whatsapp","com.boursorama.android.clients","com.android.vending","COM.WHATSAPP"]}""")
    assertEquals(listOf("com.whatsapp"), s.notifApps)
    assertEquals(listOf("com.whatsapp"), EventSettings.fromJson(s.toJson().toString()).notifApps)
  }

  @Test fun json_illisible_donne_les_defauts() {
    assertTrue(EventSettings.fromJson("{pas du json").enabled)
  }

  // ─── Texte de tiers ─────────────────────────────────────────────────────

  @Test fun texte_remonte_sur_une_ligne_et_tronque() {
    assertEquals("a b c", clipEventText("a\n\tb\u0000  c", 80))
    val long = clipEventText("x".repeat(500), 200)
    assertEquals(200, long.length)
    assertTrue(long.endsWith("…"))
    assertEquals("", clipEventText(null, 10))
    assertNotNull(clipEventText("ok", 10))
  }
}
