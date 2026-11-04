package dev.aura.mobile.wear

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.aura.mobile.AuraApp
import dev.aura.mobile.data.AuraBus
import dev.aura.mobile.data.ConfirmQueue
import dev.aura.mobile.data.PendingConfirm
import dev.aura.mobile.notify.Haptics
import dev.aura.mobile.notify.Notifier
import dev.aura.mobile.protocol.ConfirmCancel
import dev.aura.mobile.protocol.ConfirmRequest
import dev.aura.mobile.protocol.ConfirmResponse
import dev.aura.mobile.protocol.Paths
import dev.aura.mobile.protocol.Protocol
import dev.aura.mobile.protocol.SendOutcome
import dev.aura.mobile.ui.ConfirmActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cycle de vie d'une demande de confirmation : une seule réponse REMISE par `action_id`.
 *
 * 0.2.0 (17/10) : une demande n'est réglée qu'une fois la réponse partie vers le téléphone. Un envoi échoué la laisse
 * ouverte (écran, notification) : l'utilisateur peut réessayer tant que le délai court, et il le sait (vibration longue,
 * « Téléphone injoignable »). Avant, l'écran se fermait et l'échec n'était qu'un `Log.w`.
 */
object ConfirmCenter {
  private const val TAG = "AuraConfirm"

  /** `action_id` déjà réglés (répondus, annulés ou expirés) : garantit une seule réponse et ferme les écrans restants. */
  val settled = MutableStateFlow<Set<String>>(emptySet())

  /** Un envoi à la fois (écran et bouton de notification en même temps) : le 2e voit la demande déjà réglée. */
  private val sendLock = Mutex()

  private fun settle(actionId: String): Boolean {
    var first = false
    settled.update { ids ->
      first = actionId !in ids
      (ids + actionId).toList().takeLast(50).toSet()
    }
    return first
  }

  private fun forget(context: Context, actionId: String) {
    AuraBus.confirms.update { ConfirmQueue.remove(it, actionId) }
    // la complication ouvre la demande en attente : son action de toucher suit la liste
    StatusSurfaces.refresh(context)
  }

  fun pending(actionId: String): PendingConfirm? = ConfirmQueue.find(AuraBus.confirms.value, actionId)

  /** La demande à rouvrir (tuile, complication, écran principal) : la plus ancienne non échue, ou null. */
  fun oldestLive(): PendingConfirm? = ConfirmQueue.oldestLive(AuraBus.confirms.value, System.currentTimeMillis())

  fun onRequest(context: Context, request: ConfirmRequest) {
    if (request.actionId in settled.value) return
    val timeout = request.timeoutS.coerceIn(1, 600)
    val now = System.currentTimeMillis()
    val pending = PendingConfirm(request.copy(timeoutS = timeout), now + timeout * 1000L)
    AuraBus.confirms.update { ConfirmQueue.add(it, pending, now) }
    StatusSurfaces.refresh(context)
    Haptics.vibrate(context, Haptics.DOUBLE)
    // Notification plein écran (fonctionne app fermée / écran éteint) + lancement direct si l'app est visible.
    Notifier.showConfirm(context, pending)
    if (AuraApp.isForeground || !Notifier.canNotify(context)) {
      runCatching {
        context.startActivity(ConfirmActivity.intent(context, pending).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }.onFailure { Log.w(TAG, "startActivity", it) }
    }
  }

  fun onCancel(context: Context, cancel: ConfirmCancel) {
    settle(cancel.actionId)
    forget(context, cancel.actionId)
    Notifier.cancelConfirm(context, cancel.actionId)
  }

  /**
   * Envoie la réponse au téléphone et rend le résultat. La demande n'est réglée (notification retirée, écrans fermés)
   * que si le message est parti ; sur échec elle reste ouverte et l'appelant le dit à l'utilisateur.
   */
  suspend fun respondNow(context: Context, actionId: String, ok: Boolean): SendOutcome = sendLock.withLock {
    if (actionId in settled.value) {
      Log.i(TAG, "réponse ignorée pour $actionId (déjà réglée ou annulée)")
      Notifier.cancelConfirm(context, actionId)
      return@withLock SendOutcome.ALREADY_SETTLED
    }
    // Si le processus a redémarré depuis la demande, la liste est vide : on répond quand même,
    // le téléphone ignore une réponse tardive (« la première réponse gagne »).
    val sent = AuraApp.get(context).phone.send(Paths.CONFIRM_RESPONSE, Protocol.encode(ConfirmResponse(actionId, ok)))
    if (!sent) {
      Log.w(TAG, "confirm_response non remis ($actionId) : demande laissée ouverte")
      AuraBus.phoneReachable.value = false
      return@withLock SendOutcome.FAILED
    }
    settle(actionId)
    forget(context, actionId)
    Notifier.cancelConfirm(context, actionId)
    SendOutcome.SENT
  }

  /**
   * Échéance atteinte sans réponse remise : refus automatique (comportement d'avant 0.2.0), au mieux. La demande est
   * réglée localement même si le refus ne part pas : le téléphone l'expire de son côté.
   */
  fun expire(context: Context, actionId: String) {
    AuraApp.get(context).scope.launch {
      respondNow(context, actionId, false)
      settle(actionId)
      forget(context, actionId)
      Notifier.cancelConfirm(context, actionId)
    }
  }

  /** Accord perdu : l'envoi a échoué alors que le délai est tombé (l'écran se ferme) ; l'utilisateur doit le savoir. */
  fun lost(context: Context, pending: PendingConfirm?) {
    Haptics.vibrate(context, Haptics.LONG)
    Notifier.showConfirmUnreachable(context, pending, retry = false)
  }
}
