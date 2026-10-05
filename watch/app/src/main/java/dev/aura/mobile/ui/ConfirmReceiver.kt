package dev.aura.mobile.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.aura.mobile.AuraApp
import dev.aura.mobile.wear.ConfirmCenter
import kotlinx.coroutines.launch

/** Boutons Oui / Non de la notification de confirmation (secours si l'activité ne s'ouvre pas). */
class ConfirmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val actionId = intent.getStringExtra(EXTRA_ACTION_ID) ?: return
    val ok = intent.getBooleanExtra(EXTRA_OK, false)
    val pending = goAsync()
    AuraApp.get(context).scope.launch {
      try {
        ConfirmCenter.respondNow(context.applicationContext, actionId, ok)
      } finally {
        pending.finish()
      }
    }
  }

  companion object {
    private const val EXTRA_ACTION_ID = "action_id"
    private const val EXTRA_OK = "ok"

    fun intent(context: Context, actionId: String, ok: Boolean): Intent =
      Intent(context, ConfirmReceiver::class.java).putExtra(EXTRA_ACTION_ID, actionId).putExtra(EXTRA_OK, ok)
  }
}
