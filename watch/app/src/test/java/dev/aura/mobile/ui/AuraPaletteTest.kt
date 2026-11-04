package dev.aura.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuraPaletteTest {
  private fun aa(fg: Long, bg: Long, what: String) {
    val r = AuraPalette.contrast(fg, bg)
    assertTrue("$what : contraste %.2f < 4,5".format(r), r >= 4.5)
  }

  @Test fun jetons_de_l_app() {
    // phone/src/theme.generated.ts, darkTokens
    assertEquals(0xFFA395FFL, AuraPalette.ACCENT)
    assertEquals(0xFF130D33L, AuraPalette.ON_ACCENT)
    assertEquals(0xFF82F2E8L, AuraPalette.CYAN)
    assertEquals(0xFF4ADE80L, AuraPalette.OK)
    assertEquals(0xFFF87171L, AuraPalette.DANGER)
    assertEquals(0xFFFB923CL, AuraPalette.ALERT)
    assertEquals(0xFF9CA3B6L, AuraPalette.MUTED)
    assertEquals(0xFF000000L, AuraPalette.BG)  // écart voulu : noir OLED
  }

  @Test fun contraste_aa_du_texte_sur_fond_noir() {
    for ((c, n) in listOf(
      AuraPalette.TEXT to "texte", AuraPalette.MUTED to "atténué", AuraPalette.ACCENT to "accent", AuraPalette.CYAN to "cyan",
      AuraPalette.OK to "ok", AuraPalette.DANGER to "danger", AuraPalette.ALERT to "alerte",
    )) {
      aa(c, AuraPalette.BG, n)
    }
  }

  @Test fun contraste_aa_du_texte_pose_sur_une_couleur() {
    aa(AuraPalette.ON_ACCENT, AuraPalette.ACCENT, "bouton micro")
    aa(AuraPalette.ON_STATUS, AuraPalette.OK, "✓ / pastille à valider")
    aa(AuraPalette.ON_STATUS, AuraPalette.DANGER, "✗ / enregistrement")
    aa(AuraPalette.ON_ALERT, AuraPalette.ALERT, "pastille Reprendre")
    aa(AuraPalette.TEXT, AuraPalette.LINE, "pastille Pause de la tuile")
    aa(AuraPalette.MUTED, AuraPalette.LINE, "détail sur pastille")
    aa(AuraPalette.MUTED, AuraPalette.SURFACE, "atténué sur surface")
  }

  @Test fun contraste_bornes() {
    assertEquals(21.0, AuraPalette.contrast(0xFFFFFFFFL, 0xFF000000L), 0.01)
    assertEquals(1.0, AuraPalette.contrast(AuraPalette.ACCENT, AuraPalette.ACCENT), 0.0001)
  }
}
