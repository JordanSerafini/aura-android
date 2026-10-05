package dev.aura.mobile.device

import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Confirmations (action_request.confirm) : notification heads-up + ecran de l'app + carte sur la montre.
 * La premiere reponse gagne ; les autres surfaces recoivent l'annulation.
 */
class ConfirmManager {
  class Pending(val id: String, val action: String, val summary: String, val deadline: Long, val notifId: Int) {
    val latch = CountDownLatch(1)
    @Volatile var outcome: String? = null
  }

  private val pending = ConcurrentHashMap<String, Pending>()

  /** Bloque jusqu'a la reponse. Rend accepted | refused | timeout | cancelled. */
  fun ask(id: String, action: String, summary: String, timeoutS: Int): String {
    val wait = timeoutS.coerceIn(5, 600)
    val p = Pending(id, action, summary, System.currentTimeMillis() + wait * 1000L, Notifs.newId())
    pending[id] = p
    showNotification(p, wait)
    Aura.watch.sendToWatches("/aura/confirm_request",
      JSONObject().put("action_id", id).put("summary", summary).put("timeout_s", wait))
    emit()
    if (!p.latch.await(wait.toLong(), TimeUnit.SECONDS)) resolve(id, "timeout", "delai")
    return p.outcome ?: "timeout"
  }

  /** false si deja reglee ailleurs (premiere reponse gagnante). */
  fun resolve(id: String, outcome: String, source: String): Boolean {
    val p = pending.remove(id) ?: return false
    // la montre repond ok:false quand SA minuterie expire (PROTOCOL.md §6) : pres de l'echeance, c'est un
    // delai depasse, pas un refus de l'utilisateur (Aura ne doit pas lire « il a dit non »)
    p.outcome = if (source == "watch" && outcome == "refused" && System.currentTimeMillis() >= p.deadline - 1500) "timeout"
    else outcome
    p.latch.countDown()
    Notifs.cancel(Aura.app, p.notifId)
    if (source != "watch") Aura.watch.sendToWatches("/aura/confirm_cancel", JSONObject().put("action_id", id))
    emit()
    return true
  }

  fun cancel(id: String) = resolve(id, "cancelled", "server")

  fun count(): Int = pending.size

  fun listJson(): String = JSONArray(pending.values.sortedBy { it.deadline }.map {
    JSONObject().put("action_id", it.id).put("action", it.action).put("summary", it.summary).put("deadline", it.deadline)
  }).toString()

  private fun emit() {
    Aura.emit("onConfirms", listJson())
    WatchStatus.push()  // la tuile de la montre affiche le nombre de confirmations en attente
  }

  private fun showNotification(p: Pending, waitS: Int) {
    val ctx = Aura.app
    val ok = Notifs.receiverIntent(ctx, p.notifId * 2, ActionReceiver.ACTION_CONFIRM_OK, mapOf("action_id" to p.id))
    val no = Notifs.receiverIntent(ctx, p.notifId * 2 + 1, ActionReceiver.ACTION_CONFIRM_NO, mapOf("action_id" to p.id))
    val b = NotificationCompat.Builder(ctx, Notifs.CH_CONFIRM)
      .setSmallIcon(R.drawable.ic_aura)
      .setContentTitle("Aura demande ton accord")
      .setContentText(p.summary)
      .setStyle(NotificationCompat.BigTextStyle().bigText(p.summary))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_REMINDER)
      .setOngoing(true)
      .setAutoCancel(false)
      .setTimeoutAfter(waitS * 1000L)
      .setContentIntent(Notifs.launchAppIntent(ctx))
      .addAction(0, "Refuser", no)
      .addAction(0, "Accepter", ok)
    Notifs.post(ctx, p.notifId, b)
  }
}
