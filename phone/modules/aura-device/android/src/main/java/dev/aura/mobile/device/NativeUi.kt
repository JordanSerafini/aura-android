package dev.aura.mobile.device

import android.content.Context
import android.content.res.ColorStateList
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
 * Petites briques de vues natives (surcouche de l'assistant, feuille de partage) : ces ecrans vivent
 * hors de React Native (session d'assistant, activite de partage legere), aux couleurs de l'app
 * (src/config.ts : theme).
 */
object NativeUi {
  val BG = Color.parseColor("#101418")
  val CARD = Color.parseColor("#182028")
  val BORDER = Color.parseColor("#26303a")
  val TEXT = Color.parseColor("#e8edf2")
  val DIM = Color.parseColor("#8a97a6")
  val ACCENT = Color.parseColor("#6b8ff5")
  val OK = Color.parseColor("#4ade80")
  val BAD = Color.parseColor("#f87171")

  fun dp(ctx: Context, v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

  fun rounded(color: Int, radiusPx: Float, stroke: Int? = null, strokePx: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusPx
    if (stroke != null) setStroke(strokePx, stroke)
  }

  fun text(ctx: Context, size: Float, color: Int = TEXT, bold: Boolean = false): TextView = TextView(ctx).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    setLineSpacing(0f, 1.15f)
  }

  /** Bouton plein (primary) ou contour (ghost), avec retour tactile. */
  fun button(ctx: Context, label: String, kind: String = "primary", onClick: () -> Unit): TextView {
    val r = dp(ctx, 12f).toFloat()
    val bg = when (kind) {
      "ghost" -> rounded(Color.TRANSPARENT, r, BORDER, dp(ctx, 1f))
      "danger" -> rounded(BAD, r)
      "chip" -> rounded(Color.parseColor("#1d2a44"), dp(ctx, 16f).toFloat())
      else -> rounded(ACCENT, r)
    }
    return text(ctx, if (kind == "chip") 13f else 15f, if (kind == "ghost") ACCENT else Color.WHITE, kind != "chip").apply {
      this.text = label
      gravity = Gravity.CENTER
      val padH = dp(ctx, if (kind == "chip") 12f else 16f)
      val padV = dp(ctx, if (kind == "chip") 7f else 11f)
      setPadding(padH, padV, padH, padV)
      background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33ffffff")), bg, null)
      isClickable = true
      isFocusable = true
      contentDescription = label
      setOnClickListener { onClick() }
    }
  }

  fun row(ctx: Context, gapDp: Float = 8f): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    dividerDrawable = GradientDrawable().apply { setSize(dp(ctx, gapDp), 1); setColor(Color.TRANSPARENT) }
  }

  fun column(ctx: Context, gapDp: Float = 10f): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    dividerDrawable = GradientDrawable().apply { setSize(1, dp(ctx, gapDp)); setColor(Color.TRANSPARENT) }
  }

  fun weight(v: View, w: Float = 1f): View {
    v.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
    return v
  }
}
