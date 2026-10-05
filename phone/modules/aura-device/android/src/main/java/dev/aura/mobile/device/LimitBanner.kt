package dev.aura.mobile.device

import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Limite d'usage et reprise automatique (PROTOCOL.md §11.C, logique : LimitLogic.kt) : notification DISCRÈTE (canal « Infos »,
 * sans son ni heads-up) « Limite atteinte — Aura reprend à 19:30 », avec « Annuler la reprise » (`limit_cancel`).
 * `retrying` : « Aura reprend maintenant » (brève, elle s'efface seule) ; `limit_cleared` : supprimée. Sans reprise prévue
 * (limite mensuelle, heure illisible) : seulement le message, pas de bouton.
 */
object LimitBanner {
  private const val ID = 0x4500
  /** Le `limit_cleared` suit `retrying` immédiatement : on laisse « Aura reprend » visible quelques secondes. */
  private const val RETRY_SHOW_MS = 6_000L
  private val retrying = ConcurrentHashMap<String, Boolean>()

  private fun tag(conv: String) = "limit:$conv"

  /** Thread Aura.io. `silent` : rappel d'une limite déjà connue (welcome) : pas de nouvel avertissement. */
  @Suppress("MissingPermission")
  fun onState(msg: JSONObject) {
    val e = LimitLogic.parse(msg) ?: return
    val ctx = Aura.app
    retrying[e.conv] = e.retrying
    if (!Notifs.canPost(ctx)) return
    val public = NotificationCompat.Builder(ctx, Notifs.CH_INFO).setSmallIcon(R.drawable.ic_aura).setContentTitle("Limite d'Aura atteinte").build()
    val b = NotificationCompat.Builder(ctx, Notifs.CH_INFO)
      .setSmallIcon(R.drawable.ic_aura)
      .setContentTitle(LimitLogic.title(e))
      .setContentText(LimitLogic.body(e))
      .setStyle(NotificationCompat.BigTextStyle().bigText(LimitLogic.body(e)))
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_STATUS)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(public)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setContentIntent(Notifs.deepLink(ctx, ID + e.conv.hashCode(), "aura://conv/${e.conv}"))
    if (LimitLogic.canCancel(e)) {
      b.addAction(0, "Annuler la reprise", Notifs.receiverIntent(ctx, ID + e.conv.hashCode(), ActionReceiver.ACTION_LIMIT_CANCEL, mapOf("conv" to e.conv)))
    }
    if (e.retrying) b.setTimeoutAfter(RETRY_SHOW_MS)
    try {
      NotificationManagerCompat.from(ctx).notify(tag(e.conv), ID, b.build())
    } catch (ex: SecurityException) {
      // POST_NOTIFICATIONS retiree entre le test et l'appel
    }
    Log.i(Aura.TAG, "limit_state ${e.kind} retry=${e.retryAt != null} retrying=${e.retrying}")
  }

  /** `limit_cleared` : reprise faite, annulée ou abandonnée. Après un `retrying`, « Aura reprend » s'éteint seule. */
  fun onCleared(msg: JSONObject) {
    val conv = if (msg.isNull("conv")) null else msg.opt("conv") as? String ?: return
    if (conv.isNullOrEmpty() || conv.length > 64) return
    val wasRetrying = retrying.remove(conv) == true
    if (wasRetrying) {
      Aura.timer.schedule(Runnable { NotificationManagerCompat.from(Aura.app).cancel(tag(conv), ID) }, RETRY_SHOW_MS, TimeUnit.MILLISECONDS)
    } else {
      NotificationManagerCompat.from(Aura.app).cancel(tag(conv), ID)
    }
  }

  /** `welcome.limits` : une bannière par reprise encore en attente. */
  fun onWelcome(msg: JSONObject) {
    val arr = msg.optJSONArray("limits") ?: return
    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { onState(it) }
  }

  /** Bouton « Annuler la reprise » : `limit_cancel {conv}` ; la bannière tombe quand le bridge répond `limit_cleared`. */
  fun cancel(conv: String): Boolean {
    if (conv.isEmpty() || conv.length > 64) return false
    Usage.count("notif.annuler_reprise")
    return Aura.bridge.send(JSONObject().put("type", "limit_cancel").put("conv", conv))
  }
}
