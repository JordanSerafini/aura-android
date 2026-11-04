// Généré par scripts/sync-theme.mjs depuis pwa/app.css : ne pas éditer à la main.
// Relancer après un changement des jetons de la PWA : node scripts/sync-theme.mjs
package dev.aura.mobile.device

/** Jetons de la charte (22/10) : les mêmes que la PWA et React Native. Couleurs en ARGB, tailles en dp/sp, durées en ms. */
object AuraTokens {
  class Palette(
    val bg: Int,
    val surface: Int,
    val surface2: Int,
    val text: Int,
    val muted: Int,
    val line: Int,
    val lineStrong: Int,
    val accent: Int,
    val auraViolet: Int,
    val auraCyan: Int,
    val accentText: Int,
    val alertText: Int,
    val me: Int,
    val alert: Int,
    val danger: Int,
    val ok: Int,
    val gaugeOk: Int,
    val gaugeMid: Int,
    val gaugeHigh: Int,
    val tokKw: Int,
    val tokStr: Int,
    val tokCom: Int,
    val tokNum: Int,
    val tokTag: Int,
    val tokAttr: Int,
    val diffAdd: Int,
    val diffAddBg: Int,
    val diffDel: Int,
    val diffDelBg: Int,
    val focusRing: Int,
    val onAura: Int,
  )

  val Light = Palette(
    bg = 0xFFF3F4F8.toInt(),
    surface = 0xFFFFFFFF.toInt(),
    surface2 = 0xFFFFFFFF.toInt(),
    text = 0xFF141620.toInt(),
    muted = 0xFF5A6072.toInt(),
    line = 0xFFDEE1EA.toInt(),
    lineStrong = 0xFF848A9A.toInt(),
    accent = 0xFF5544D4.toInt(),
    auraViolet = 0xFF8B73FF.toInt(),
    auraCyan = 0xFF68E1DF.toInt(),
    accentText = 0xFFFFFFFF.toInt(),
    alertText = 0xFFFFFFFF.toInt(),
    me = 0xFFEBE8FD.toInt(),
    alert = 0xFFC2410C.toInt(),
    danger = 0xFFB42318.toInt(),
    ok = 0xFF15803D.toInt(),
    gaugeOk = 0xFF2EA85F.toInt(),
    gaugeMid = 0xFFB06F12.toInt(),
    gaugeHigh = 0xFFB42318.toInt(),
    tokKw = 0xFF7C3AED.toInt(),
    tokStr = 0xFF0F7B3F.toInt(),
    tokCom = 0xFF5F6771.toInt(),
    tokNum = 0xFFB45309.toInt(),
    tokTag = 0xFF1D5FC2.toInt(),
    tokAttr = 0xFF9A3412.toInt(),
    diffAdd = 0xFF116329.toInt(),
    diffAddBg = 0xFFE3F4E8.toInt(),
    diffDel = 0xFFA40E26.toInt(),
    diffDelBg = 0xFFFDE7E9.toInt(),
    focusRing = 0xFF5544D4.toInt(),
    onAura = 0xFF0C0B1D.toInt(),
  )

  val Dark = Palette(
    bg = 0xFF0E1017.toInt(),
    surface = 0xFF161925.toInt(),
    surface2 = 0xFF1C2030.toInt(),
    text = 0xFFECEEF6.toInt(),
    muted = 0xFFB0B6C6.toInt(),
    line = 0xFF262B3A.toInt(),
    lineStrong = 0xFF6A7188.toInt(),
    accent = 0xFFA395FF.toInt(),
    auraViolet = 0xFFA99AFF.toInt(),
    auraCyan = 0xFF82F2E8.toInt(),
    accentText = 0xFF130D33.toInt(),
    alertText = 0xFF1C1206.toInt(),
    me = 0xFF262650.toInt(),
    alert = 0xFFFB923C.toInt(),
    danger = 0xFFF87171.toInt(),
    ok = 0xFF4ADE80.toInt(),
    gaugeOk = 0xFF2EA85F.toInt(),
    gaugeMid = 0xFFB06F12.toInt(),
    gaugeHigh = 0xFFF87171.toInt(),
    tokKw = 0xFFC4A5FF.toInt(),
    tokStr = 0xFF8FD6A0.toInt(),
    tokCom = 0xFF9CA3B6.toInt(),
    tokNum = 0xFFF5A962.toInt(),
    tokTag = 0xFF7FB3FF.toInt(),
    tokAttr = 0xFFF0B775.toInt(),
    diffAdd = 0xFF7EE2A8.toInt(),
    diffAddBg = 0xFF12301D.toInt(),
    diffDel = 0xFFFFA0A0.toInt(),
    diffDelBg = 0xFF3A1719.toInt(),
    focusRing = 0xFFA395FF.toInt(),
    onAura = 0xFF0C0B1D.toInt(),
  )

  /** Espacements (dp), grille de 4. */
  object Space {
    const val S1 = 4f
    const val S2 = 8f
    const val S3 = 12f
    const val S4 = 16f
    const val S5 = 20f
    const val S6 = 24f
    const val S7 = 32f
    const val S8 = 40f
  }

  /** Rayons (dp). */
  object Radius {
    const val CARD = 16f
    const val SM = 8f
    const val MD = 12f
    const val XL = 24f
    const val LG = 16f
    const val PILL = 999f
  }

  /** Tailles de texte (sp) : 12 méta, 14 secondaire, 16 corps, 18 titre de carte, 24 titre d'écran. */
  object Type {
    const val XS = 12f
    const val SM = 14f
    const val MD = 14f
    const val BASE = 16f
    const val LG = 18f
    const val XL = 24f
  }

  /** Durées du mouvement (ms). */
  object Motion {
    const val FAST = 120L
    const val BASE = 160L
    const val SLOW = 300L
    const val VIEW = 220L
    const val SHEET = 340L
    const val PRESS = 120L
    const val ENTER = 200L
    const val PANEL = 300L
  }

  /** Cible tactile minimale (dp). */
  const val TAP = 48f
}
