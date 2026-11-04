package dev.aura.mobile.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Charte UI du 22/10 (lot 19) : le natif lit les jetons générés, tient les contrastes et suit le thème choisi. */
class CharteTokensTest {
  private fun lum(c: Int): Double {
    fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.04045) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
    return 0.2126 * ch((c shr 16) and 255) + 0.7152 * ch((c shr 8) and 255) + 0.0722 * ch(c and 255)
  }
  private fun ratio(a: Int, b: Int): Double { val x = maxOf(lum(a), lum(b)); val y = minOf(lum(a), lum(b)); return (x + 0.05) / (y + 0.05) }

  private fun pairs(p: AuraTokens.Palette): List<Triple<String, Double, Double>> {
    val out = mutableListOf<Triple<String, Double, Double>>()
    for ((n, bg) in listOf("bg" to p.bg, "surface" to p.surface, "surface2" to p.surface2)) {
      out += Triple("text/$n", ratio(p.text, bg), 4.5)
      out += Triple("muted/$n", ratio(p.muted, bg), 4.5)
      out += Triple("accent/$n", ratio(p.accent, bg), 4.5)
      out += Triple("lineStrong/$n", ratio(p.lineStrong, bg), 3.0)
      out += Triple("danger/$n", ratio(p.danger, bg), 4.5)
    }
    out += Triple("accentText/accent", ratio(p.accentText, p.accent), 4.5)   // bouton « Envoyer » (3,06:1 avant)
    out += Triple("alertText/danger", ratio(p.alertText, p.danger), 4.5)
    out += Triple("text/me", ratio(p.text, p.me), 4.5)                       // puces de la bulle
    return out
  }

  @Test fun contrastesSombre() {
    val bad = pairs(AuraTokens.Dark).filter { it.second < it.third }
    assertTrue("échecs sombre : $bad", bad.isEmpty())
  }

  @Test fun contrastesClair() {
    val bad = pairs(AuraTokens.Light).filter { it.second < it.third }
    assertTrue("échecs clair : $bad", bad.isEmpty())
  }

  @Test fun palettesEtEchelles() {
    assertEquals(0xFF0E1017.toInt(), AuraTokens.Dark.bg)           // pas de noir pur
    assertEquals(0xFFB0B6C6.toInt(), AuraTokens.Dark.muted)        // décision 2 du 22/10
    assertTrue(AuraTokens.Dark.surface2 != AuraTokens.Dark.surface) // élévation par la teinte
    assertEquals(listOf(12f, 14f, 16f, 18f, 24f), listOf(NativeUi.XS, NativeUi.SM, NativeUi.BASE, NativeUi.LG, NativeUi.XL))
    assertEquals(48f, AuraTokens.TAP)
    assertEquals(16f, AuraTokens.Radius.LG)
  }

  @Test fun themeChoisiPuisSysteme() {
    assertTrue(NativeUi.isDark("dark", false))
    assertFalse(NativeUi.isDark("light", true))
    assertTrue(NativeUi.isDark("auto", true))
    assertFalse(NativeUi.isDark(null, false))
  }

  @Test fun alphaGardeLaTeinte() {
    assertEquals(0x80A395FF.toInt(), NativeUi.alpha(0xFFA395FF.toInt(), 128 / 255f))
    assertEquals(0x00A395FF, NativeUi.alpha(0xFFA395FF.toInt(), 0f))
  }
}
