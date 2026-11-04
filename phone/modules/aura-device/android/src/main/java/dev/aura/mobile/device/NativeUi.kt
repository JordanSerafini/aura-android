package dev.aura.mobile.device

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Petites briques de vues natives (surcouche de l'assistant, feuille de partage, activités) : ces
 * écrans vivent hors de React Native. Charte du 22/10 (lot 19) : toutes les couleurs, tailles, rayons et durées viennent
 * d'[AuraTokens], généré depuis les jetons de la PWA comme le thème React Native. Plus aucune palette recopiée ici.
 *
 * Thème : celui choisi dans Réglages → Apparence de la PWA (pref `theme_pref`, relayée par l'app), sinon celui du système.
 * Les couleurs sont lues au moment où la vue est construite : chaque écran appelle [sync] en entrant (fait aussi par
 * [text] et [button], premières briques de tout écran).
 */
object NativeUi {
  @Volatile var palette: AuraTokens.Palette = AuraTokens.Dark
    private set

  /** Sombre si la pref de l'app le force, ou si le système est en sombre (pref « auto » ou absente). */
  fun isDark(pref: String?, systemNight: Boolean): Boolean = when (pref) {
    "light" -> false
    "dark" -> true
    else -> systemNight
  }

  fun sync(ctx: Context) {
    val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val pref = try { Aura.prefs.getString("js_theme_pref", null) } catch (e: Exception) { null }
    palette = if (isDark(pref, night)) AuraTokens.Dark else AuraTokens.Light
  }

  val dark: Boolean get() = palette === AuraTokens.Dark

  /** Fond d'écran. */
  val BG: Int get() = palette.bg
  /** Cartes, champs posés sur un panneau. */
  val CARD: Int get() = palette.surface
  /** Feuilles et menus (plus clair que la carte en sombre : l'élévation par la teinte). */
  val PANEL: Int get() = palette.surface2
  /** Séparateurs (décoratifs). */
  val LINE: Int get() = palette.line
  /** Bordure des contrôles (champs, boutons fantômes, puces) : 3:1 sur toutes les surfaces. */
  val BORDER: Int get() = palette.lineStrong
  val TEXT: Int get() = palette.text
  /** Texte secondaire, méta. */
  val DIM: Int get() = palette.muted
  /** LE seul accent : action primaire, sélection, lien, focus. */
  val ACCENT: Int get() = palette.accent
  val ON_ACCENT: Int get() = palette.accentText
  /** Fond doux d'une puce ou d'une sélection (bulle « moi » de la PWA). */
  val SOFT: Int get() = palette.me
  val OK: Int get() = palette.ok
  val WARN: Int get() = palette.alert
  val BAD: Int get() = palette.danger
  /** Texte posé sur un aplat d'état (OK, WARN, BAD). */
  val ON_STATUS: Int get() = palette.alertText
  /** Identité seulement (orbe, pastille, icône) : jamais un état. */
  val VIOLET: Int get() = palette.auraViolet
  val CYAN: Int get() = palette.auraCyan
  /** Voile sous une feuille ou un dialogue. */
  const val SCRIM = 0x80080818.toInt()

  /** Échelle de texte (sp) : méta 12, secondaire 14, corps 16, titre de carte 18, titre d'écran 24. */
  const val XS = AuraTokens.Type.XS
  const val SM = AuraTokens.Type.SM
  const val BASE = AuraTokens.Type.BASE
  const val LG = AuraTokens.Type.LG
  const val XL = AuraTokens.Type.XL

  private var monoFace: Typeface? = null

  /** Geist Mono Medium (res/font, copie de phone/assets/fonts/GeistMono-Medium.ttf, OFL 1.1, licence geist-mono-OFL.txt ; charte du 22/10, lot 20) : heures, compteurs, durées, mesures. Chiffres de même chasse. */
  fun mono(ctx: Context): Typeface = monoFace
    ?: (try { ctx.resources.getFont(R.font.geist_mono_medium) } catch (e: Exception) { Typeface.MONOSPACE }).also { monoFace = it }

  fun alpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

  fun dp(ctx: Context, v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

  fun rounded(color: Int, radiusPx: Float, stroke: Int? = null, strokePx: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusPx
    if (stroke != null) setStroke(strokePx, stroke)
  }

  /** Retour tactile du thème : le texte à 12 %, lisible sur fond clair comme sombre (l'ancien blanc fixe disparaissait en clair). */
  fun ripple(content: android.graphics.drawable.Drawable?): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(alpha(TEXT, 0.12f)), content, null)

  fun text(ctx: Context, size: Float, color: Int? = null, bold: Boolean = false): TextView {
    return TextView(ctx).apply {
      setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
      setTextColor(color ?: TEXT)
      if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
      setLineSpacing(0f, 1.2f)
    }
  }

  /** Bouton : état désactivé visible (opacité 0,45), comme les boutons React Native de l'app. */
  class Button(ctx: Context) : TextView(ctx) {
    override fun setEnabled(enabled: Boolean) {
      super.setEnabled(enabled)
      alpha = if (enabled) 1f else 0.45f
    }
  }

  /** Bouton plein (primary), contour (ghost), danger ou puce, avec retour tactile ; hauteur tactile 48 dp. */
  fun button(ctx: Context, label: String, kind: String = "primary", onClick: () -> Unit): TextView {
    sync(ctx)
    val r = dp(ctx, AuraTokens.Radius.MD).toFloat()
    val bg = when (kind) {
      "ghost" -> rounded(Color.TRANSPARENT, r, BORDER, dp(ctx, 1f))
      "danger" -> rounded(BAD, r)
      "chip" -> rounded(SOFT, dp(ctx, AuraTokens.Radius.PILL).toFloat())
      else -> rounded(ACCENT, r)
    }
    val fg = when (kind) {
      "ghost" -> ACCENT
      "chip" -> TEXT
      "danger" -> ON_STATUS
      else -> ON_ACCENT
    }
    return Button(ctx).apply {
      setTextSize(TypedValue.COMPLEX_UNIT_SP, if (kind == "chip") SM else BASE)
      setTextColor(fg)
      if (kind != "chip") typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
      this.text = label
      gravity = Gravity.CENTER
      val padH = dp(ctx, if (kind == "chip") AuraTokens.Space.S3 else AuraTokens.Space.S4)
      val padV = dp(ctx, AuraTokens.Space.S2)
      setPadding(padH, padV, padH, padV)
      minHeight = dp(ctx, if (kind == "chip") 40f else AuraTokens.TAP)
      background = ripple(bg)
      isClickable = true
      isFocusable = true
      contentDescription = label
      setOnClickListener { onClick() }
    }
  }

  fun row(ctx: Context, gapDp: Float = AuraTokens.Space.S2): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    dividerDrawable = GradientDrawable().apply { setSize(dp(ctx, gapDp), 1); setColor(Color.TRANSPARENT) }
  }

  fun column(ctx: Context, gapDp: Float = AuraTokens.Space.S3): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    dividerDrawable = GradientDrawable().apply { setSize(1, dp(ctx, gapDp)); setColor(Color.TRANSPARENT) }
  }

  fun weight(v: View, w: Float = 1f): View {
    v.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
    return v
  }
}
