package dev.aura.mobile.data

/**
 * Repli TYPE_STEP_COUNTER : le capteur donne un cumul depuis le démarrage.
 * On mémorise la valeur du compteur au premier relevé du jour ; un compteur inférieur à la base
 * signale un redémarrage de la montre, la base repart alors de zéro.
 */
data class StepBaseline(val day: String, val counter: Long) {
  companion object {
    /** Rend (pas du jour, base à mémoriser). */
    fun compute(stored: StepBaseline?, today: String, counter: Long): Pair<Long, StepBaseline> {
      if (stored == null || stored.day != today) return 0L to StepBaseline(today, counter)
      if (counter < stored.counter) return counter to StepBaseline(today, 0L)
      return (counter - stored.counter) to stored
    }
  }
}
