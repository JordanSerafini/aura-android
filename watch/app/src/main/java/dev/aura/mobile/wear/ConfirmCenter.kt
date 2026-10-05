package dev.aura.mobile.wear

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.aura.mobile.AuraApp
import dev.aura.mobile.data.AuraBus
import dev.aura.mobile.data.PendingConfirm
import dev.aura.mobile.notify.Haptics
import dev.aura.mobile.notify.Notifier
import dev.aura.mobile.protocol.ConfirmCancel
import dev.aura.mobile.protocol.ConfirmRequest
import dev.aura.mobile.protocol.ConfirmResponse
import dev.aura.mobile.protocol.Paths
import dev.aura.mobile.protocol.Protocol
import dev.aura.mobile.ui.ConfirmActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Cycle de vie d'une demande de confirmation : une seule réponse par `action_id`. */
object ConfirmCenter {
  private const val TAG = "AuraConfirm"

  /** `action_id` déjà réglés (répondus ou annulés) : garantit une seule réponse et ferme les écrans restants. */
  val settled = MutableStateFlow<Set<String>>(emptySet())

  private fun settle(actionId: String): Boolean {
    var first = false
    settled.update { ids ->
      first = actionId !in ids
      (ids + actionId).toList().takeLast(50).toSet()
    }
    return first
  }

  fun onRequest(context: Context, request: ConfirmRequest) {
    if (request.actionId in settled.value) return
    val timeout = request.timeoutS.coerceIn(1, 600)
    val pending = PendingConfirm(request.copy(timeoutS = timeout), System.currentTimeMillis() + timeout * 1000L)
    AuraBus.confirm.value = pending
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
    if (AuraBus.confirm.value?.request?.actionId == cancel.actionId) AuraBus.confirm.value = null
    Notifier.cancelConfirm(context, cancel.actionId)
  }

  /** Répond au téléphone (une seule fois) et ferme la demande. */
  fun respond(context: Context, actionId: String, ok: Boolean) {
    val app = AuraApp.get(context)
    app.scope.launch { respondNow(context, actionId, ok) }
  }

  /** Version suspendue (utilisée par le BroadcastReceiver via goAsync). Rend true si la réponse est partie. */
  suspend fun respondNow(context: Context, actionId: String, ok: Boolean): Boolean {
    Notifier.cancelConfirm(context, actionId)
    if (!settle(actionId)) {
      Log.i(TAG, "réponse ignorée pour $actionId (déjà réglée ou annulée)")
      return false
    }
    // Si le processus a redémarré depuis la demande, AuraBus est vide : on répond quand même,
    // le téléphone ignore une réponse tardive (« la première réponse gagne »).
    if (AuraBus.confirm.value?.request?.actionId == actionId) AuraBus.confirm.value = null
    val sent = AuraApp.get(context).phone.send(Paths.CONFIRM_RESPONSE, Protocol.encode(ConfirmResponse(actionId, ok)))
    if (!sent) Log.w(TAG, "confirm_response non remis ($actionId)")
    return sent
  }
}
