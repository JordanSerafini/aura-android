package dev.aura.mobile.device

import android.view.HapticFeedbackConstants

/**
 * Haptique semantique (17/10 soir) : la PWA dit l'INTENTION (`{type: "haptic", kind}`), l'app joue l'effet systeme
 * par `View.performHapticFeedback`, qui suit le reglage « retour haptique » du telephone (un Vibrator direct l'ignorerait)
 * et donne les clics courts et nets recommandes par la doc Android, la ou `navigator.vibrate` ne fait qu'un bourdonnement.
 * Logique pure (testee sur la JVM) : intention -> constante ; inconnue = null, on ne joue rien plutot qu'un effet faux.
 */
object HapticLogic {
  val KINDS = setOf("tick", "confirm", "reject", "heavy")

  fun constant(kind: String, sdk: Int): Int? = when (kind) {
    "tick" -> HapticFeedbackConstants.CLOCK_TICK
    "confirm" -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    "reject" -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    "heavy" -> HapticFeedbackConstants.LONG_PRESS
    else -> null
  }
}
