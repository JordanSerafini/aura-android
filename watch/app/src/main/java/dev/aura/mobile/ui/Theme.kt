package dev.aura.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

object AuraColors {
  val accent = Color(0xFF7C4DFF)
  val onAccent = Color.White
  val recording = Color(0xFFE53935)
  val ok = Color(0xFF2E7D32)
  val ko = Color(0xFFC62828)
  val warn = Color(0xFFFFB74D)
  val muted = Color(0xFFB9A8FF)
}

@Composable
fun AuraTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = ColorScheme(primary = AuraColors.accent, onPrimary = AuraColors.onAccent),
    content = content,
  )
}
