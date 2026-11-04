package dev.aura.mobile.data

/**
 * Demandes d'accord que la montre connaît encore (0.2.0) : avant, AuraBus ne gardait que la dernière, et la pastille
 * « 2 à valider » ne savait rouvrir qu'elle. Liste pure, triée par échéance (la plus ancienne d'abord, à durées égales),
 * testée en JVM (ConfirmQueueTest).
 */
object ConfirmQueue {
  /** Au-delà, les plus anciennes tombent (le téléphone les a de toute façon expirées). */
  const val MAX = 10

  /** Ajoute (ou remplace, même `action_id`) une demande ; retire au passage les demandes échues. */
  fun add(list: List<PendingConfirm>, pending: PendingConfirm, nowMs: Long): List<PendingConfirm> =
    (prune(list, nowMs).filterNot { it.request.actionId == pending.request.actionId } + pending)
      .sortedBy { it.deadlineMs }
      .takeLast(MAX)

  fun remove(list: List<PendingConfirm>, actionId: String): List<PendingConfirm> =
    list.filterNot { it.request.actionId == actionId }

  /** Demandes encore ouvertes (échéance dans le futur). */
  fun prune(list: List<PendingConfirm>, nowMs: Long): List<PendingConfirm> = list.filter { it.deadlineMs > nowMs }

  /** La plus ancienne demande non échue (celle qui expire le plus tôt), ou null. */
  fun oldestLive(list: List<PendingConfirm>, nowMs: Long): PendingConfirm? = prune(list, nowMs).minByOrNull { it.deadlineMs }

  fun find(list: List<PendingConfirm>, actionId: String): PendingConfirm? = list.firstOrNull { it.request.actionId == actionId }
}
