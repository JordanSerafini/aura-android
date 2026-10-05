package dev.aura.mobile.audio

/** Volume média de la montre + boost logiciel (LoudnessEnhancer), tel qu'affiché. */
data class VolumeLevel(val volume: Int, val max: Int, val boost: Int)

enum class VolumeKey { LOUDER, QUIETER }

sealed interface VolumeAction {
  data object Raise : VolumeAction
  data object Lower : VolumeAction
  data class SetBoost(val level: Int) : VolumeAction
  data object None : VolumeAction
}

/**
 * Logique pure des boutons − / + (et de la lunette) : le boost n'a de sens qu'au volume média maximum.
 * Sous le max (volume baissé par la lunette système ou le panneau rapide), le boost mémorisé ne compte plus.
 */
object VolumeLogic {
  const val BOOST_MAX = 3

  fun effectiveBoost(volume: Int, max: Int, boost: Int): Int = if (max > 0 && volume >= max) boost.coerceIn(0, BOOST_MAX) else 0

  fun decide(volume: Int, max: Int, boost: Int, key: VolumeKey): VolumeAction {
    val b = effectiveBoost(volume, max, boost)
    return when (key) {
      VolumeKey.LOUDER -> when {
        volume < max -> VolumeAction.Raise
        b < BOOST_MAX -> VolumeAction.SetBoost(b + 1)
        else -> VolumeAction.None
      }
      VolumeKey.QUIETER -> when {
        volume >= max && b > 0 -> VolumeAction.SetBoost(b - 1)
        volume > 0 -> VolumeAction.Lower
        else -> VolumeAction.None
      }
    }
  }

  /** « Volume 7/15 », « Volume max + boost 2/3 » ou « Son coupé ». */
  fun label(level: VolumeLevel): String {
    val b = effectiveBoost(level.volume, level.max, level.boost)
    return when {
      b > 0 -> "Volume max + boost $b/$BOOST_MAX"
      level.volume <= 0 -> "Son coupé"
      else -> "Volume ${level.volume}/${level.max}"
    }
  }
}
