package dev.aura.mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudWatchTest {
  private val now = 1_792_660_000L

  @Test fun etatDuTelephone19Lu() {
    val json = """{"pause":{"mode":"off"},"confirms":0,"bridge":"online","updated":1,
      "hud":{"n":14,"graves":1,"todo":3,"rdv":"14:00 · Client exemple","rdv_ts":1792670400,"at":${now - 60}}}"""
    val s = Protocol.json.decodeFromString(AuraStatus.serializer(), json)
    val m = HudWatch.model(s, now)
    assertEquals("14 alertes · 1 grave", m.headline)
    assertEquals(HudWatch.Level.GRAVE, m.level)
    assertEquals("⚠14", m.shortText)
    assertEquals("14:00", m.title)
    assertEquals("3 à traiter", m.todo)
  }

  @Test fun telephoneAncienSansHud() {
    val s = Protocol.json.decodeFromString(AuraStatus.serializer(), """{"bridge":"online","updated":1}""")
    assertNull(s.hud)
    assertEquals(HudWatch.Level.UNKNOWN, HudWatch.model(s, now).level)
  }

  @Test fun hudVieuxEtCalme() {
    val vieux = AuraStatus(hud = HudBrief(n = 2, at = now - 25 * 60))
    assertEquals("PC injoignable", HudWatch.model(vieux, now).headline)
    assertEquals("?", HudWatch.model(vieux, now).shortText)
    val calme = AuraStatus(hud = HudBrief(at = now))
    assertEquals("Rien à signaler", HudWatch.model(calme, now).headline)
    assertEquals("Aucun rendez-vous", HudWatch.model(calme, now).rdv)
    assertEquals("HUD", HudWatch.model(calme, now).title)
  }
}
