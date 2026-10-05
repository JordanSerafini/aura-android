package dev.aura.mobile.ui

import dev.aura.mobile.protocol.States

/** États locaux de la montre (en plus des états protocole `/aura/state`). */
object LocalStates {
  const val RECORDING = "recording"
  const val SENDING = "sending"
  const val PHONE_UNREACHABLE = "phone_unreachable"
  const val NO_ANSWER = "no_answer"
  const val RECORD_FAILED = "record_failed"
}

object StateLabels {
  fun label(state: String?): String? = when (state) {
    null -> null
    LocalStates.RECORDING -> "Enregistrement…"
    LocalStates.SENDING -> "Envoi au téléphone…"
    LocalStates.PHONE_UNREACHABLE -> "Téléphone injoignable"
    LocalStates.NO_ANSWER -> "Pas de nouvelles du téléphone"
    LocalStates.RECORD_FAILED -> "Micro indisponible"
    States.SENT -> "Envoyé"
    States.TRANSCRIBING -> "Transcription…"
    States.THINKING -> "Réflexion…"
    States.DONE -> "Terminé"
    States.ERROR -> "Erreur"
    States.OFFLINE -> "PC hors ligne"
    else -> state
  }

  fun isBusy(state: String?) = state in setOf(LocalStates.SENDING, States.SENT, States.TRANSCRIBING, States.THINKING)

  fun isError(state: String?) =
    state in setOf(States.ERROR, States.OFFLINE, LocalStates.PHONE_UNREACHABLE, LocalStates.NO_ANSWER, LocalStates.RECORD_FAILED)
}
