package dev.aura.mobile.protocol

/** « Nouvelle conversation » lancée depuis la montre, en attente d'un `/aura/convs` avec `created == req`. */
data class NewConv(val req: String, val status: String = NewConvStatus.PENDING)

object NewConvStatus {
  const val PENDING = "pending"
  /** L'envoi montre -> S22 a échoué. */
  const val PHONE_UNREACHABLE = "phone_unreachable"
  /** Le S22 a répondu `error = "offline"`, ou rien en [NewConvLogic.TIMEOUT_MS]. */
  const val PC_OFFLINE = "pc_offline"
}

enum class NewConvOutcome { NONE, CREATED, PC_OFFLINE }

object NewConvLogic {
  const val TIMEOUT_MS = 15_000L

  /** Effet d'une liste reçue sur la création en attente (le S22 pousse aussi des listes spontanées). */
  fun onList(pending: NewConv?, list: ConvList): NewConvOutcome = when {
    pending == null || pending.status != NewConvStatus.PENDING -> NewConvOutcome.NONE
    list.created == pending.req -> NewConvOutcome.CREATED
    list.pcOffline -> NewConvOutcome.PC_OFFLINE
    else -> NewConvOutcome.NONE
  }
}
