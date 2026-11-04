package dev.aura.mobile.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Widget « Aura HUD » (22/10) : forme du flux `hud_feed` envoye par le serveur (PROTOCOL.md §33). */
class HudLogicTest {
  private val zone = ZoneId.of("Europe/Paris")
  private fun ms(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 22, h, m, 0, 0, zone).toInstant().toEpochMilli()
  private val now = ms(8, 45)

  private val feed = JSONObject("""
    {"v": 1, "ts": 1792651160, "mode": "travail",
     "noyau": {"n": 14, "graves": 1, "niveau": "grave"},
     "meteo": {"t": 7, "txt": "Plutôt dégagé"},
     "rdv": {"jour": "Aujourd'hui", "h": "09:00", "t": "Point compta", "lieu": ""},
     "alertes": [
       {"id": "sonde:deps", "nom": "deps", "detail": "app: 1 critical", "grave": true},
       {"id": "tache:pytest-73", "nom": "pytest", "detail": "2 échecs", "grave": false, "tache": "pytest-73"}],
     "planning": [
       {"j": "Aujourd'hui", "d": "2026-10-22", "n": 2, "evs": [
         {"h": "09:00", "fin": "11:00", "t": "Point compta", "k": "rdv", "lieu": "", "passe": false},
         {"h": "13:50", "fin": "", "t": "Rappeler", "k": "rappel", "lieu": "", "passe": false, "id": "e4c8a65925"}]},
       {"j": "Demain", "d": "2026-10-23", "n": 0, "evs": []}],
     "a_traiter": [
       {"kind": "rappel", "id": "34115249a3", "t": "Renouveler le certificat", "quand": ${ms(8, 0) / 1000}},
       {"kind": "mail", "id": "AAQk=", "t": "FOURNISSEUR EXEMPLE", "d": "Migration", "age_h": 21.2},
       {"kind": "promesse", "id": "0000-01dd", "t": "0600000000", "d": "WhatsApp Business"},
       {"kind": "rappel", "id": "5cbedef135", "t": "Appeler Paul", "quand": ${ms(18, 0) / 1000}}],
     "systeme": {"ram": 38, "swap": 45, "charge": 46, "disque": {"nom": "data", "pct": 93}, "ko": []},
     "superviseur": {"age_min": 10, "marqueur": "NOFIX", "resume": "Rien à réparer", "en_cours_s": null},
     "missions": [{"title": "Widget HUD", "step": "build APK", "since": ${now / 1000 - 600}, "waiting": false}],
     "note": ""}
  """.trimIndent())

  @Test fun `taille selon les dimensions du lanceur`() {
    assertEquals(HudLogic.Size.SMALL, HudLogic.size(150, 150))
    assertEquals(HudLogic.Size.MEDIUM, HudLogic.size(320, 180))
    assertEquals(HudLogic.Size.LARGE, HudLogic.size(320, 400))
    assertEquals(HudLogic.Size.SMALL, HudLogic.size(150, 400))  // etroit et haut : le petit, pas une liste illisible
  }

  @Test fun `en-tete - noyau, prochain rendez-vous, meteo, fraicheur`() {
    val h = HudLogic.header(feed, ms(8, 41), now, zone)
    assertEquals("14", h.core)
    assertEquals(HudLogic.Level.GRAVE, h.level)
    assertEquals("09:00 · Point compta", h.next)
    assertEquals("7° Plutôt dégagé", h.weather)
    assertEquals("à 08:41", h.fresh)
    assertFalse(h.offline)
    assertEquals("14 alerte(s), dont 1 grave(s)", h.coreA11y)
  }

  @Test fun `hors ligne lisible et jamais recu sans zero invente`() {
    val h = HudLogic.header(feed, ms(8, 20), now, zone)
    assertEquals("PC injoignable · il y a 25 min", h.fresh)
    assertTrue(h.offline)
    val vide = HudLogic.header(null, 0L, now, zone)
    assertEquals("–", vide.core)
    assertEquals(HudLogic.Level.INCONNU, vide.level)
    assertTrue(vide.offline)
  }

  @Test fun `format moyen - les 3 plus urgentes, dans l ordre du bridge, avec leurs boutons`() {
    val top = HudLogic.topTodo(feed, now, zone)
    assertEquals(3, top.size)
    assertTrue(top[0].title.contains("Renouveler le certificat"))
    assertEquals(HudLogic.Tone.RED, top[0].tone)  // echu
    assertEquals("échu · 08:00", top[0].detail)
    assertEquals(listOf("FAIT", "+1H"), top[0].buttons.map { it.label })
    assertEquals(listOf("TRAITÉ"), top[1].buttons.map { it.label })
    assertEquals("mail_seen", top[1].buttons[0].action)
    assertEquals(listOf("FAIT", "ÉCARTER"), top[2].buttons.map { it.label })
    assertTrue(top[2].buttons[1].strong)
  }

  @Test fun `grand format - sections dans l ordre du HUD du PC`() {
    val rows = HudLogic.rows(feed, now, zone)
    val sections = rows.filter { it.section }.map { it.title }
    assertEquals(listOf("ALERTES · 14", "PLANNING · AUJOURD'HUI", "PLANNING · DEMAIN", "À TRAITER · 4", "SYSTÈME", "SUPERVISEUR",
      "EN COURS · 1"), sections)
    val tache = rows.first { it.key == "al:tache:pytest-73" }
    assertEquals(listOf("RELANCER", "RÉPARE", "MASQUER 24H"), tache.buttons.map { it.label })
    assertEquals("aura://hub", tache.open)
    assertEquals(listOf("RÉPARE", "MASQUER 24H"), rows.first { it.key == "al:sonde:deps" }.buttons.map { it.label })
    val rdv = rows.first { it.key == "plan:2026-10-22:0" }
    assertEquals("09:00–11:00", rdv.time)
    assertTrue(rdv.buttons.isEmpty())
    assertEquals(listOf("reminder_done", "reminder_snooze"), rows.first { it.key == "plan:2026-10-22:1" }.buttons.map { it.action })
    assertEquals("Rien de prévu", rows.first { it.key == "plan:2026-10-23:0" }.title)
    assertTrue(rows.first { it.key == "sys" }.detail.contains("data 93 %"))
    assertEquals(HudLogic.Tone.AMBER, rows.first { it.key == "sys" }.tone)  // disque a 93 %
    assertEquals("Widget HUD · 10 min", rows.first { it.key == "mission:0" }.title)
    assertFalse(rows.any { it.key == "s:note" })  // bloc-notes vide : pas de section
  }

  @Test fun `liens - chaque ligne ouvre l app au bon endroit`() {
    val rows = HudLogic.rows(feed, now, zone)
    assertEquals("aura://view/rappels", rows.first { it.key == "todo:rappel:34115249a3" }.open)
    assertEquals("aura://view/actions", rows.first { it.key == "todo:mail:AAQk=" }.open)
    assertEquals("aura://view/chat", rows.first { it.key == "sup" }.open)
    assertTrue(rows.all { it.open.startsWith("aura://") })
  }

  @Test fun `actions fortes - deux appuis dans les 6 s, les autres partent tout de suite`() {
    assertEquals(HudLogic.Tap.Run("reminder_done", "34115249a3"), HudLogic.tap("reminder_done", "34115249a3", null, now))
    val arm = HudLogic.tap("ignore", "sonde:deps", null, now)
    assertTrue(arm is HudLogic.Tap.Arm)
    val armed = (arm as HudLogic.Tap.Arm).key to arm.until
    assertEquals(HudLogic.Tap.Run("ignore", "sonde:deps"), HudLogic.tap("ignore", "sonde:deps", armed, now + 3_000))
    assertTrue(HudLogic.tap("ignore", "sonde:deps", armed, now + 7_000) is HudLogic.Tap.Arm)  // trop tard : re-arme
    assertTrue(HudLogic.tap("ignore", "sonde:autre", armed, now + 1_000) is HudLogic.Tap.Arm)  // arme sur une autre ligne
    assertTrue(HudLogic.tap("heal", "", null, now) is HudLogic.Tap.Arm)
    assertTrue(HudLogic.tap("promesse_ignore", "0000-01dd", null, now) is HudLogic.Tap.Arm)
    assertTrue(HudLogic.tap("qbit", "abc", null, now) is HudLogic.Tap.Refuse)
    assertTrue(HudLogic.tap("mail_seen", "", null, now) is HudLogic.Tap.Refuse)
  }

  @Test fun `libelles - arme et en cours`() {
    val b = HudLogic.Btn("MASQUER 24H", "ignore", "sonde:deps", HudLogic.Tone.MUTED, true)
    assertEquals("MASQUER 24H", HudLogic.label(b, null, null, now))
    assertEquals("CONFIRMER ?", HudLogic.label(b, "ignore|sonde:deps" to now + 5_000, null, now))
    assertEquals("MASQUER 24H", HudLogic.label(b, "ignore|sonde:deps" to now - 1, null, now))
    assertEquals("…", HudLogic.label(b, null, "ignore|sonde:deps", now))
  }

  @Test fun `action vers message du bridge`() {
    val body = HudLogic.actionBody("promesse_done", "0000-01dd")
    assertEquals("promesse_done", body.getString("action"))
    assertEquals("0000-01dd", body.getString("id"))
    assertEquals(setOf("reminder_done", "reminder_snooze", "task_run", "heal", "ignore", "mail_seen", "promesse_done",
      "promesse_ignore"), HudLogic.ACTIONS)
  }

  @Test fun `url HTTP tiree de l url WebSocket`() {
    assertEquals("https://aura.example.org", HudLogic.httpBase("wss://aura.example.org/ws"))
    assertEquals("http://10.0.0.5:19825", HudLogic.httpBase("ws://10.0.0.5:19825/ws"))
    assertEquals("https://aura.example.org", HudLogic.httpBase("wss://aura.example.org/ws?x=1#y"))
  }

  @Test fun `flux absent ou partiel ne casse rien`() {
    assertEquals("Pas encore de données", HudLogic.rows(null, now, zone).single().title)
    val maigre = JSONObject("""{"v": 1, "noyau": {"n": 0, "graves": 0, "niveau": "ok"}}""")
    val rows = HudLogic.rows(maigre, now, zone)
    assertEquals("Rien à signaler", rows[1].title)
    assertTrue(rows.any { it.title == "Rien en attente" })
    assertEquals("Aucun rendez-vous à venir", HudLogic.header(maigre, now, now, zone).next)
    assertEquals("Aucune alerte", HudLogic.header(maigre, now, now, zone).coreA11y)
  }
}
