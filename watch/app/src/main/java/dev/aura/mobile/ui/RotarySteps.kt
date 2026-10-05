package dev.aura.mobile.ui

import kotlin.math.abs

/**
 * Convertit les événements de la lunette en pas de volume : sur une lunette à crans (Galaxy Watch Classic,
 * `android.hardware.rotaryencoder.lowres`) un cran = un pas ; sur une couronne fluide, un pas tous les [stepPx].
 */
class RotarySteps(private val lowRes: Boolean, private val stepPx: Float = STEP_PX) {
  private var acc = 0f

  /** Rend le nombre de pas signé (> 0 = sens horaire = plus fort). */
  fun feed(px: Float): Int {
    if (px == 0f) return 0
    if (lowRes || abs(px) >= stepPx) {
      acc = 0f
      return if (px > 0) 1 else -1
    }
    if (acc * px < 0) acc = 0f // changement de sens : on repart de zéro
    acc += px
    val steps = (acc / stepPx).toInt()
    acc -= steps * stepPx
    return steps
  }

  companion object {
    const val STEP_PX = 48f
    const val LOW_RES_FEATURE = "android.hardware.rotaryencoder.lowres"
  }
}
