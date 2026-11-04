package dev.aura.mobile.protocol

/*
 * Écran de confirmation de la montre (0.2.0, 17/10) : machine d'états pure, testée en JVM (ConfirmFlowTest).
 *
 * Avant 0.2.0, « Accepter » fermait l'écran tout de suite et un `confirm_response` qui ne partait pas (téléphone hors de
 * portée) n'était qu'un `Log.w` : l'accord était perdu sans aucun signal. Désormais l'écran attend le résultat de l'envoi ;
 * un échec le laisse ouvert (boutons réactivés, vibration longue) tant que le délai court.
 */

/** Résultat d'un envoi de réponse au téléphone (ConfirmCenter.respondNow). */
enum class SendOutcome {
  /** Le message est parti vers le téléphone : la demande est réglée. */
  SENT,
  /** Téléphone injoignable ou envoi refusé par le Data Layer : rien n'est réglé, on peut réessayer. */
  FAILED,
  /** Déjà réglée (répondue ailleurs, annulée ou expirée) : rien n'est parti, rien à faire. */
  ALREADY_SETTLED,
}

object ConfirmFlow {
  sealed interface State

  /** En attente de la réponse de l'utilisateur : boutons actifs. */
  data object Ready : State

  /**
   * Réponse en cours d'envoi : boutons désactivés, « Envoi… ». `expired` : le délai est tombé pendant l'envoi ;
   * `attempts` : échecs déjà subis avant cet envoi.
   */
  data class Sending(val ok: Boolean, val expired: Boolean = false, val attempts: Int = 0) : State

  /** Dernier envoi échoué : boutons réactivés, message d'échec ; une nouvelle tentative est possible tant que le délai court. */
  data class Failed(val attempts: Int) : State

  /** Écran à fermer. */
  data object Closed : State

  sealed interface Event

  /** Appui sur ✓ (`ok = true`) ou ✗. */
  data class Tap(val ok: Boolean) : Event

  data class Result(val outcome: SendOutcome) : Event

  /** Échéance atteinte. */
  data object Expired : Event

  /** Réglée ailleurs (téléphone, autre surface, bouton de notification) ou annulée par le téléphone. */
  data object SettledElsewhere : Event

  sealed interface Effect

  /** Envoyer la réponse au téléphone (puis renvoyer un [Result]). */
  data class Send(val ok: Boolean) : Effect

  /** Échec pendant que l'écran reste ouvert : vibration longue. */
  data object FailureAlert : Effect

  /** Échec alors que le délai est tombé pendant l'envoi : l'écran se ferme, une notification + vibration le disent. */
  data object LostNotice : Effect

  /** Expiration sans réponse en cours : refus automatique (comportement d'avant 0.2.0), au mieux. */
  data object ExpiryRefusal : Effect

  data class Transition(val state: State, val effects: List<Effect> = emptyList())

  fun next(state: State, event: Event): Transition = when (state) {
    Closed -> Transition(Closed)
    Ready, is Failed -> when (event) {
      is Tap -> Transition(Sending(event.ok, attempts = (state as? Failed)?.attempts ?: 0), listOf(Send(event.ok)))
      Expired -> Transition(Closed, listOf(ExpiryRefusal))
      SettledElsewhere -> Transition(Closed)
      // pas d'envoi en cours : un résultat tardif (écran recréé) ne change rien
      is Result -> Transition(state)
    }
    is Sending -> when (event) {
      // un seul envoi à la fois : un 2e appui pendant l'envoi est ignoré
      is Tap -> Transition(state)
      Expired -> Transition(state.copy(expired = true))
      SettledElsewhere -> Transition(Closed)
      is Result -> when (event.outcome) {
        SendOutcome.SENT, SendOutcome.ALREADY_SETTLED -> Transition(Closed)
        SendOutcome.FAILED ->
          if (state.expired) {
            Transition(Closed, listOf(LostNotice))
          } else {
            Transition(Failed(state.attempts + 1), listOf(FailureAlert))
          }
      }
    }
  }

  /** Boutons ✓ / ✗ actifs. */
  fun buttonsEnabled(state: State): Boolean = state == Ready || state is Failed

  const val FAILURE_TEXT = "Téléphone injoignable, réponds sur le téléphone"

  /** Ligne du haut de l'écran : compte à rebours, « Envoi… » ou l'échec. */
  fun headline(state: State, remainingMs: Long): String {
    val s = (remainingMs.coerceAtLeast(0) + 999) / 1000
    return when (state) {
      is Sending -> "Envoi…"
      is Failed -> "$FAILURE_TEXT · $s s"
      else -> "Confirmer ? $s s"
    }
  }
}
