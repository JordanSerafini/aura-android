package dev.aura.mobile.device

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Carte d'appel (`call_card` du bridge, PROTOCOL.md §10) : fiche client a la sonnerie, en notification heads-up sur le
 * telephone et poussee vers la montre. Le contenu est une donnee externe : texte seul, aucun lien, aucun bouton qui en
 * dependrait ; il n'est ecrit nulle part (ni journal, ni logcat) au-dela de la notification elle-meme, qui s'efface
 * toute seule. Aucun enregistrement audio.
 */
object CallCards {
  private const val ID = 0x4100
  private const val TAG_CARD = "call_card"
  private const val TTL_MIN = 3L

  /** Thread Aura.io. */
  fun onBridge(msg: JSONObject) {
    if (Pause.blocksEvents()) return  // pause totale : pas d'appel vers Aura, donc pas de carte non plus
    val card = CallCardLogic.parse(msg) ?: return
    if (!CallCardLogic.fresh(card.ts, System.currentTimeMillis() / 1000)) return
    show(Aura.app, card)
    Usage.count("fiche_appel.affichee")
    Aura.watch.sendToWatches("/aura/call_card", CallCardLogic.toWatch(card))
    Log.i(Aura.TAG, "call_card affichee")  // jamais son contenu
  }

  @Suppress("MissingPermission")
  private fun show(ctx: Context, card: CallCard) {
    if (!Notifs.canPost(ctx)) return
    val body = CallCardLogic.body(card)
    val open: PendingIntent? = Notifs.launchAppIntent(ctx)
    // la carte contient une fiche client : rien sur l'ecran verrouille (version publique minimale)
    val public = NotificationCompat.Builder(ctx, Notifs.CH_CALL)
      .setSmallIcon(R.drawable.ic_aura).setContentTitle("Appel entrant").build()
    val b = NotificationCompat.Builder(ctx, Notifs.CH_CALL)
      .setSmallIcon(R.drawable.ic_aura)
      .setContentTitle(card.title)
      .setContentText(CallCardLogic.summary(card))
      .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifEmpty { "Appel entrant" }))
      .setSubText(card.number)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_STATUS)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(public)
      .setLocalOnly(true)  // la montre recoit sa propre carte (/aura/call_card) : pas de doublon par le miroir Wear OS
      .setOnlyAlertOnce(true)
      .setAutoCancel(true)
      .setTimeoutAfter(TimeUnit.MINUTES.toMillis(TTL_MIN))
      .setContentIntent(open)
      .addAction(0, "Ouvrir dans Aura", open)
    try {
      NotificationManagerCompat.from(ctx).notify(TAG_CARD, ID, b.build())
    } catch (e: SecurityException) {
      // POST_NOTIFICATIONS retiree entre le test et l'appel
    }
  }
}
