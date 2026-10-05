package dev.aura.mobile.device

import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fin d'une tâche d'Aura (`task_done`, PROTOCOL.md §11.D, logique : FinLogic.kt) : heads-up « Aura a fini : <titre> ».
 *
 *  - pas si l'app est au premier plan sur l'onglet Aura ou sur Talk : la réponse est sous les yeux de l'utilisateur. Le natif ne sait
 *    pas QUELLE conversation montre la PWA de l'onglet (elle vit dans une WebView) : c'est la PWA qui déclare `viewing {conv}`
 *    au bridge, depuis sa propre connexion (même appareil). Ici on ne sait que « Aura est visible » ;
 *  - pas de doublon avec la notification `aura-fin` que le bridge pousse quand PERSONNE ne regarde : il diffuse `task_done`
 *    d'abord, puis `aura-fin`, donc le heads-up natif attend 2,5 s (FinLogic.GRACE_MS) et s'efface devant elle (FinDedup).
 */
object TaskDone {
  private const val ID = 0x4400
  private val dedup = FinDedup()

  /** Notification `aura-fin` du bridge reçue (Notifs.showBridgeNotify) : elle vaut le heads-up de cette conversation. */
  fun onBridgeFin(conv: String?) {
    if (!conv.isNullOrEmpty()) dedup.onBridgeFin(conv, System.currentTimeMillis())
  }

  /** Thread Aura.io. */
  fun onBridge(msg: JSONObject) {
    val e = FinLogic.parse(msg) ?: return
    if (!FinLogic.shouldShow(Aura.appForeground, LiveUpdate.screen)) return
    Aura.timer.schedule(Runnable { show(e) }, FinLogic.GRACE_MS, TimeUnit.MILLISECONDS)
  }

  @Suppress("MissingPermission")
  private fun show(e: TaskDoneEvent) {
    val ctx = Aura.app
    if (!Notifs.canPost(ctx)) return
    if (dedup.duplicate(e.conv, System.currentTimeMillis())) {
      Log.i(Aura.TAG, "task_done : la notification aura-fin du bridge suffit")
      return
    }
    // app passée au premier plan pendant l'attente : plus de heads-up
    if (!FinLogic.shouldShow(Aura.appForeground, LiveUpdate.screen)) return
    val public = NotificationCompat.Builder(ctx, Notifs.CH_DONE).setSmallIcon(R.drawable.ic_aura)
      .setContentTitle(if (e.ok) "Aura a fini une tâche" else "Une tâche d'Aura a échoué").build()
    val b = NotificationCompat.Builder(ctx, Notifs.CH_DONE)
      .setSmallIcon(R.drawable.ic_aura)
      .setContentTitle(FinLogic.title(e))
      .setContentText(FinLogic.body(e))
      .setStyle(NotificationCompat.BigTextStyle().bigText(FinLogic.body(e)))
      .setSubText(FinLogic.elapsedLabel(e.elapsedS))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_STATUS)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)  // titre du chat et résumé : pas sur l'écran verrouillé
      .setPublicVersion(public)
      .setAutoCancel(true)
      .setContentIntent(Notifs.deepLink(ctx, ID + e.conv.hashCode(), "aura://conv/${e.conv}"))
    try {
      NotificationManagerCompat.from(ctx).notify("fin:${e.conv}", ID, b.build())
    } catch (ex: SecurityException) {
      // POST_NOTIFICATIONS retiree entre le test et l'appel
    }
    Log.i(Aura.TAG, "task_done affichee")  // jamais le titre ni le resume
  }
}
