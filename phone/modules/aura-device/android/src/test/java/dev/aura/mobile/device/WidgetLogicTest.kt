package dev.aura.mobile.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WidgetLogicTest {
  private val zone = ZoneId.of("Europe/Paris")
  private fun ms(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()

  @Test fun `jamais recu ne dit pas zero`() {
    val t = WidgetLogic.texts(0, 0, 0L, ms(17, 21, 40), zone)
    assertEquals("–", t.actions)
    assertEquals("Ouvre Aura pour actualiser", t.at)
  }

  @Test fun `le jour meme on dit l heure, la veille la date`() {
    assertEquals("à 21:40", WidgetLogic.at(ms(17, 21, 40), ms(17, 23, 5), zone))
    assertEquals("le 17/10 à 21:40", WidgetLogic.at(ms(17, 21, 40), ms(18, 8, 0), zone))
  }

  @Test fun `plus de neuf devient 9+ et le lecteur d ecran entend le vrai nombre`() {
    val t = WidgetLogic.texts(12, 3, ms(17, 21, 40), ms(17, 21, 41), zone)
    assertEquals("9+", t.actions)
    assertEquals("3", t.notifs)
    assertTrue(t.actionsA11y.startsWith("À traiter : 12 maintenant, à 21:40"))
    assertEquals("À traiter : rien maintenant, à 21:40. Ouvrir", WidgetLogic.texts(0, 0, ms(17, 21, 40), ms(17, 21, 41), zone).actionsA11y)
  }
}
