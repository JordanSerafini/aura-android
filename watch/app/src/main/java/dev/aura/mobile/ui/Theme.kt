package dev.aura.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/** Couleurs Compose de la montre : jetons sombres de l'app sur fond noir OLED (AuraPalette). */
object AuraColors {
  val background = Color(AuraPalette.BG)
  val text = Color(AuraPalette.TEXT)
  val accent = Color(AuraPalette.ACCENT)
  val onAccent = Color(AuraPalette.ON_ACCENT)
  val cyan = Color(AuraPalette.CYAN)
  val recording = Color(AuraPalette.DANGER)
  val ok = Color(AuraPalette.OK)
  val ko = Color(AuraPalette.DANGER)
  val warn = Color(AuraPalette.ALERT)
  val onWarn = Color(AuraPalette.ON_ALERT)
  val muted = Color(AuraPalette.MUTED)
  /** Fond discret (pastille d'information). */
  val surfaceLine = Color(AuraPalette.LINE)
  /** Texte et icônes sombres sur ok / danger. */
  val onStatus = Color(AuraPalette.ON_STATUS)
}

@Composable
fun AuraTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = ColorScheme(
      primary = AuraColors.accent,
      onPrimary = AuraColors.onAccent,
      secondary = AuraColors.cyan,
      onSecondary = AuraColors.onStatus,
      error = AuraColors.ko,
      onError = AuraColors.onStatus,
      background = AuraColors.background,
      onBackground = AuraColors.text,
      onSurface = AuraColors.text,
      onSurfaceVariant = AuraColors.muted,
    ),
    content = content,
  )
}
