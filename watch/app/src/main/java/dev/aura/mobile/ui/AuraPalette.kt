package dev.aura.mobile.ui

/**
 * Couleurs de la montre, alignées sur les jetons SOMBRES de l'app (`phone/src/theme.generated.ts`, `darkTokens`, 0.2.0) :
 * accent #a395ff, cyan, ok, danger, alerte. Seul écart voulu : le fond reste NOIR (#000000, et non #0e1017) : écran
 * OLED, un pixel noir est éteint (batterie). ARGB pur (sans Compose) : partagé par le thème Compose (Theme.kt) et la tuile
 * (ProtoLayout), contraste AA vérifié en JVM (AuraPaletteTest).
 */
object AuraPalette {
  /** Fond OLED. */
  const val BG = 0xFF000000L
  /** `surface` des jetons : cartes, fonds discrets. */
  const val SURFACE = 0xFF161925L
  /** `line` des jetons : fond des pastilles de la tuile (texte clair dessus). */
  const val LINE = 0xFF262B3AL
  const val TEXT = 0xFFECEEF6L
  const val MUTED = 0xFF9CA3B6L
  const val ACCENT = 0xFFA395FFL
  /** `accentText` : texte et icônes posés sur l'accent. */
  const val ON_ACCENT = 0xFF130D33L
  /** `auraCyan` : réponse en lecture, second ton du halo. */
  const val CYAN = 0xFF82F2E8L
  const val OK = 0xFF4ADE80L
  const val DANGER = 0xFFF87171L
  /** `alert` : avertissements (pause, échec, téléphone injoignable). */
  const val ALERT = 0xFFFB923CL
  /** `alertText` : texte posé sur l'alerte. */
  const val ON_ALERT = 0xFF1C1206L
  /** `onAura` : texte et icônes sombres posés sur ok / danger / cyan. */
  const val ON_STATUS = 0xFF0C0B1DL

  /** Luminance relative WCAG 2.x d'une couleur ARGB (alpha ignoré). */
  fun luminance(argb: Long): Double {
    fun channel(shift: Int): Double {
      val c = ((argb shr shift) and 0xFF).toDouble() / 255.0
      return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
  }

  /** Rapport de contraste WCAG (1 à 21). AA texte courant : 4,5. */
  fun contrast(a: Long, b: Long): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
  }
}
