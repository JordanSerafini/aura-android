package dev.aura.mobile.notify

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Motifs de vibration du protocole : `short`, `long`, `double`. */
object Haptics {
  const val SHORT = "short"
  const val LONG = "long"
  const val DOUBLE = "double"

  fun isKnown(pattern: String) = pattern in setOf(SHORT, LONG, DOUBLE)

  /** Forme d'onde (ms) : pause, vibration, pause, vibration... */
  fun timings(pattern: String): LongArray = when (pattern) {
    LONG -> longArrayOf(0, 700)
    DOUBLE -> longArrayOf(0, 150, 120, 150)
    else -> longArrayOf(0, 90)
  }

  fun vibrate(context: Context, pattern: String = SHORT): Boolean {
    val vibrator = vibrator(context) ?: return false
    if (!vibrator.hasVibrator()) return false
    vibrator.vibrate(VibrationEffect.createWaveform(timings(pattern), -1))
    return true
  }

  /** Tic très court (un cran de lunette sur l'écran volume). */
  fun tick(context: Context): Boolean {
    val vibrator = vibrator(context) ?: return false
    if (!vibrator.hasVibrator()) return false
    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    return true
  }

  private fun vibrator(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      context.getSystemService(Vibrator::class.java)
    }
}
