package dev.aura.mobile.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** HUD mobile max (1.9.0, 22/10) : en-tete enrichi, tailles responsives, resume, Live Update, montre (PROTOCOL.md §35). */
class HudSurfacesLogicTest {
  private val zone = ZoneId.of("Europe/Paris")
  private fun ms(h: Int, m: Int, d: Int = 22) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()

  private fun feed(graves: Boolean = true, missions: Boolean = true) = JSONObject("""
    {"v": 1, "mode": "travail",
     "noyau": {"n": ${if (graves) 2 else 1}, "graves": ${if (graves) 1 else 0}, "niveau": "${if (graves) "grave" else "alerte"}"},
     "alertes": [
       ${if (graves) """{"id": "sonde:deps", "nom": "DEPS", "detail": "1 critical", "grave": true},""" else ""}
       {"id": "tache:pytest", "nom": "PYTEST", "detail": "2 échecs", "grave": false, "tache": "pytest"}],
     "planning": [
       {"j": "Aujourd'hui", "d": "2026-10-22", "evs": [
         {"h": "09:00", "fin": "11:00", "t": "Point compta", "k": "rdv", "lieu": "", "passe": false},
         {"h": "13:50", "fin": "", "t": "Rappeler", "k": "rappel", "id": "e4c8a65925"},
         {"h": "14:00", "fin": "", "t": "Client exemple", "k": "intervention", "lieu": "Lyon"}]},
       {"j": "Demain", "d": "2026-10-23", "evs": [{"h": "08:30", "fin": "10:00", "t": "Atelier exemple", "k": "rdv", "lieu": ""}]}],
     "a_traiter": [{"kind": "mail", "id": "AAQk=", "t": "FOURNISSEUR", "d": "Migration", "age_h": 21.2}],
     "systeme": {"ram": 38, "disque": {"nom": "data", "pct": 93}, "ko": []},
     "superviseur": {"age_min": 3, "marqueur": "NOFIX", "resume": "", "en_cours_s": null},
     "missions": ${if (missions) """[{"title": "HUD mobile max", "step": "build", "since": ${ms(10, 0) / 1000}, "waiting": false}]""" else "[]"}}
  """.trimIndent())

  @Test fun rendezVousEnCoursPuisProchainPuisDemain() {
    val f = feed()
    assertEquals("En cours → 11:00 · Point compta", HudLogic.rdvLabel(HudLogic.nextRdv(f, ms(10, 48), zone)!!, ms(10, 48), zone))
    assertEquals("14:00 · Client exemple · Lyon", HudLogic.rdvLabel(HudLogic.nextRdv(f, ms(11, 1), zone)!!, ms(11, 1), zone))
    // intervention sans heure de fin = 1 h ; a 15:01 c'est demain
    assertEquals("Demain 08:30 · Atelier exemple", HudLogic.rdvLabel(HudLogic.nextRdv(f, ms(15, 1), zone)!!, ms(15, 1), zone))
    assertNull(HudLogic.nextRdv(f, ms(10, 1, 23), zone))
    // l'en-tete du widget ne montre plus « 09:00 · Point compta » a 10:48 (vu sur la capture d'avant)
    assertEquals("En cours → 11:00 · Point compta", HudLogic.header(f, ms(10, 47), ms(10, 48), zone).next)
  }

  @Test fun lignesDuMoyenSelonLaHauteur() {
    assertEquals(0, HudLogic.todoLines(110))
    assertEquals(1, HudLogic.todoLines(170))
    assertEquals(2, HudLogic.todoLines(220))
    assertEquals(3, HudLogic.todoLines(260))
    assertEquals(3, HudLogic.todoLines(600))
    // les gabarits responsifs vont du petit au grand, hauteurs croissantes pour le moyen
    val medium = HudLogic.RESPONSIVE.filter { it.second == HudLogic.Size.MEDIUM }
    assertEquals(listOf(0, 1, 2, 3), medium.map { it.third })
    assertTrue(medium.zipWithNext().all { (a, b) -> a.first.second < b.first.second })
    // chaque gabarit moyen tient ses lignes de 48 dp
    medium.forEach { assertTrue(it.first.second >= 100 + 48 * it.third - 4 || it.third == 0) }
    assertEquals(HudLogic.Size.SMALL to 0, HudLogic.pick(170f, 170f))
    assertEquals(HudLogic.Size.MEDIUM to 0, HudLogic.pick(360f, 110f))
    assertEquals(HudLogic.Size.MEDIUM to 1, HudLogic.pick(360f, 175f))
    assertEquals(HudLogic.Size.MEDIUM to 2, HudLogic.pick(360f, 200f))  // 4x2 du lanceur Pixel (~200 dp)
    assertEquals(HudLogic.Size.MEDIUM to 3, HudLogic.pick(360f, 260f))
    assertEquals(HudLogic.Size.LARGE to 0, HudLogic.pick(360f, 470f))
    assertEquals(HudLogic.Size.SMALL to 0, HudLogic.pick(60f, 60f))  // trop petit : le plus petit gabarit
  }

  @Test fun enteteStatutModeEtSante() {
    val h = HudLogic.header(feed(), ms(10, 47), ms(10, 48), zone)
    assertEquals("TRAVAIL", h.mode)
    assertEquals("⚙ Aura travaille : HUD mobile max · 48 min", h.status)
    assertEquals(HudLogic.Tone.RED, h.health)
    assertEquals(HudLogic.Tone.AMBER, HudLogic.header(feed(graves = false), ms(10, 47), ms(10, 48), zone).health)  // disque 93 %
    assertEquals(HudLogic.Tone.MUTED, HudLogic.header(feed(), ms(10, 0), ms(10, 48), zone).health)  // hors ligne : muette
    assertEquals("", HudLogic.status(feed(missions = false), ms(10, 48)))
  }

  @Test fun resumePourNotificationTuileEtMontre() {
    val b = HudLogic.brief(feed(), ms(10, 47), ms(10, 48), zone)
    assertEquals("2 alertes · 1 grave", b.title)
    assertEquals("En cours → 11:00 · Point compta · 1 à traiter", b.text)
    assertEquals("⚠ DEPS · 1 critical", b.lines.first())
    assertTrue(b.lines.size <= 7)
    assertEquals("2 alertes", HudLogic.tileSubtitle(b))
    assertEquals("Pas de données", HudLogic.tileSubtitle(HudLogic.brief(null, 0, ms(10, 48), zone)))
    assertEquals("PC injoignable", HudLogic.tileSubtitle(HudLogic.brief(feed(), ms(9, 0), ms(10, 48), zone)))
    val w = HudLogic.watchBrief(feed(), ms(10, 47), ms(10, 48), zone)!!
    assertEquals(2, w.getInt("n"))
    assertEquals(1, w.getInt("graves"))
    assertEquals(ms(9, 0) / 1000, w.getLong("rdv_ts"))
    assertNull(HudLogic.watchBrief(feed(), 0, ms(10, 48), zone))
  }

  @Test fun liveUpdatePriorites() {
    val o = HudLogic.LiveOpts()
    // alerte grave d'abord (flux frais)
    val g = HudLogic.live(feed(), ms(13, 30), ms(13, 31), zone, o, emptySet())!!
    assertEquals("grave", g.kind)
    assertEquals("⚠ DEPS", g.title)
    // « Vu » : la meme alerte ne revient pas, le rendez-vous proche prend la place (14:00, a 13:31)
    val r = HudLogic.live(feed(), ms(13, 30), ms(13, 31), zone, o, setOf(g.key))!!
    assertEquals("rdv", r.kind)
    assertEquals(ms(14, 0), r.countdownTo)
    assertEquals("14:00", r.chip)
    assertEquals(3, r.progress)  // 1 min sur 30
    // une autre alerte grave a une autre cle : elle revient malgre « Vu »
    val f2 = feed().apply { getJSONArray("alertes").put(JSONObject("""{"id": "sonde:x", "nom": "X", "grave": true}""")) }
    assertEquals("grave", HudLogic.live(f2, ms(13, 30), ms(13, 31), zone, o, setOf(g.key))!!.kind)
    // flux vieux de plus de 20 min : pas d'alerte grave (peut-etre deja reglee), le rendez-vous reste
    assertEquals("rdv", HudLogic.live(feed(), ms(13, 0), ms(13, 31), zone, o, emptySet())!!.kind)
  }

  @Test fun liveUpdateFenetreDuRendezVousEtNuit() {
    val o = HudLogic.LiveOpts(grave = false)
    assertNull(HudLogic.live(feed(), ms(13, 20), ms(13, 29), zone, o, emptySet()))  // 31 min avant : pas encore
    assertEquals("rdv", HudLogic.live(feed(), ms(13, 20), ms(13, 30), zone, o, emptySet())!!.kind)
    val started = HudLogic.live(feed(), ms(14, 2), ms(14, 3), zone, o, emptySet())!!
    assertEquals("Commencé à 14:00 · Lyon", started.text)
    assertEquals(0L, started.countdownTo)
    assertNull(HudLogic.live(feed(), ms(14, 5), ms(14, 6), zone, o, emptySet()))  // 5 min apres le debut : fini
    // nuit calme : rien, meme une alerte grave
    assertNull(HudLogic.live(feed(), ms(23, 10), ms(23, 11), zone, HudLogic.LiveOpts(), emptySet()))
    assertNull(HudLogic.live(feed(), ms(6, 58), ms(6, 59), zone, HudLogic.LiveOpts(), emptySet()))
    assertTrue(HudLogic.nightQuiet(23) && HudLogic.nightQuiet(0) && !HudLogic.nightQuiet(7) && !HudLogic.nightQuiet(22))
  }

  @Test fun liveUpdateMissionsSurOptionSeulement() {
    val noRdv = feed(graves = false).apply { put("planning", org.json.JSONArray()) }
    assertNull(HudLogic.live(noRdv, ms(10, 47), ms(10, 48), zone, HudLogic.LiveOpts(), emptySet()))
    val m = HudLogic.live(noRdv, ms(10, 47), ms(10, 48), zone, HudLogic.LiveOpts(missions = true), emptySet())
    assertNotNull(m)
    assertEquals("Aura travaille : HUD mobile max", m!!.title)
  }

  @Test fun boutonsDecritsPourTalkBack() {
    val b = HudLogic.Btn("FAIT", "reminder_done", "e4", HudLogic.Tone.GREEN, false)
    assertEquals("FAIT : Rappeler Dupont", HudLogic.btnA11y(b, "⏰ Rappeler Dupont", null, null, 0))
    assertEquals("FAIT en cours : Rappeler Dupont", HudLogic.btnA11y(b, "⏰ Rappeler Dupont", null, "reminder_done|e4", 0))
    val s = HudLogic.Btn("ÉCARTER", "promesse_ignore", "p1", HudLogic.Tone.MUTED, true)
    assertEquals("Toucher encore pour confirmer ÉCARTER : Promesse",
      HudLogic.btnA11y(s, "☎ Promesse", "promesse_ignore|p1" to 10_000L, null, 5_000L))
    assertFalse(HudLogic.btnA11y(s, "☎ Promesse", null, null, 0).contains("☎"))
  }
}
