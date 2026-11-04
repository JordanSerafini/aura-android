package dev.aura.mobile.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.aura.mobile.AuraApp
import dev.aura.mobile.data.PendingConfirm
import dev.aura.mobile.notify.Haptics
import dev.aura.mobile.notify.Notifier
import dev.aura.mobile.protocol.ConfirmRequest
import dev.aura.mobile.protocol.SendOutcome
import dev.aura.mobile.wear.ConfirmCenter
import kotlinx.coroutines.launch

/**
 * Boutons Oui / Non de la notification de confirmation (secours si l'activité ne s'ouvre pas). 0.2.0 : si la réponse
 * ne part pas, vibration longue et notification « Téléphone injoignable » (avec Oui / Non pour réessayer tant que le
 * délai court) au lieu d'un simple `Log.w`.
 */
class ConfirmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val actionId = intent.getStringExtra(EXTRA_ACTION_ID) ?: return
    val ok = intent.getBooleanExtra(EXTRA_OK, false)
    val app = context.applicationContext
    val pending = goAsync()
    AuraApp.get(context).scope.launch {
      try {
        if (ConfirmCenter.respondNow(app, actionId, ok) == SendOutcome.FAILED) {
          // la demande connue de la montre d'abord ; à défaut celle des extras (processus redémarré entre-temps)
          val known = ConfirmCenter.pending(actionId) ?: fromExtras(intent, actionId)
          Haptics.vibrate(app, Haptics.LONG)
          Notifier.showConfirmUnreachable(app, known, retry = true)
        }
      } finally {
        pending.finish()
      }
    }
  }

  companion object {
    private const val EXTRA_ACTION_ID = "action_id"
    private const val EXTRA_OK = "ok"
    private const val EXTRA_SUMMARY = "summary"
    private const val EXTRA_TIMEOUT = "timeout_s"
    private const val EXTRA_DEADLINE = "deadline_ms"

    fun intent(context: Context, pending: PendingConfirm, ok: Boolean): Intent =
      Intent(context, ConfirmReceiver::class.java)
        .putExtra(EXTRA_ACTION_ID, pending.request.actionId)
        .putExtra(EXTRA_OK, ok)
        .putExtra(EXTRA_SUMMARY, pending.request.summary)
        .putExtra(EXTRA_TIMEOUT, pending.request.timeoutS)
        .putExtra(EXTRA_DEADLINE, pending.deadlineMs)

    private fun fromExtras(intent: Intent, actionId: String): PendingConfirm? {
      val deadline = intent.getLongExtra(EXTRA_DEADLINE, 0L).takeIf { it > 0L } ?: return null
      val request = ConfirmRequest(actionId, intent.getStringExtra(EXTRA_SUMMARY).orEmpty(), intent.getIntExtra(EXTRA_TIMEOUT, 30))
      return PendingConfirm(request, deadline)
    }
  }
}
