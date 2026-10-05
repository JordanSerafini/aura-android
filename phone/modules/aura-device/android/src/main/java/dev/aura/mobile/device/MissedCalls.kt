package dev.aura.mobile.device

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

/**
 * Appels manqués regroupés (`missed_calls_card` du bridge, PROTOCOL.md §11.10, logique : MissedLogic.kt) : une notification
 * heads-up par numéro, « 3 appels manqués : ACME Test », texte seul, masquée sur l'écran verrouillé, avec « Rappeler » qui ouvre
 * le COMPOSEUR (ACTION_DIAL, aucune permission d'appel direct : l'utilisateur appuie lui-même sur Appeler).
 *
 * Elle disparaît quand le numéro est rappelé : le bridge renvoie alors la carte SANS ce groupe, et on retire la notification.
 * Pause d'Aura : la notification n'est jamais bloquée (Aura te dit quelque chose, elle n'agit pas) ; en pause TOTALE elle
 * s'affiche sans heads-up ni son, et « Rappeler » reste un geste de l'utilisateur.
 */
object MissedCalls {
  private const val ID = 0x4200
  private const val TAG_PREFIX = "missed:"
  private const val TAG_SUMMARY = "missed_summary"
  private const val GROUP = "dev.aura.mobile.MISSED"
  private const val EXTRA_COUNT = "aura.missed_count"
  private const val PREF_GROUPS = "missed_groups"

  /** Numéros à rappeler d'après la dernière carte reçue (nombre seulement, jamais un numéro : sert la complication de la montre). */
  fun groups(): Int = Aura.prefs.getInt(PREF_GROUPS, 0)

  /** Il y a (ou il y avait) quelque chose à rafraîchir : le welcome redemande la liste. */
  fun hasShown(): Boolean = groups() > 0

  /** Thread Aura.io. */
  fun onBridge(msg: JSONObject) {
    val card = MissedLogic.parse(msg) ?: return
    val ctx = Aura.app
    val before = groups()
    val posted = postedCounts(ctx)
    val plan = MissedLogic.plan(posted, card)
    val quiet = MissedLogic.quiet(card, Pause.mode())
    if (Notifs.canPost(ctx)) {
      for (key in plan.cancel) NotificationManagerCompat.from(ctx).cancel(TAG_PREFIX + key, ID)
      for (p in plan.post) post(ctx, p.group, alert = p.alert && !quiet, quiet = quiet)
      val remaining = (posted.keys - plan.cancel) + plan.post.map { MissedLogic.key(it.group) }
      if (remaining.size >= 2) {
        val calls = remaining.sumOf { k -> plan.post.firstOrNull { MissedLogic.key(it.group) == k }?.group?.count ?: posted[k] ?: 1 }
        postSummary(ctx, calls)
      } else {
        NotificationManagerCompat.from(ctx).cancel(TAG_SUMMARY, ID)
      }
    }
    val after = MissedLogic.callbacks(card)
    if (after != before) {
      Aura.prefs.edit().putInt(PREF_GROUPS, after).apply()
      WatchStatus.push()
    }
    Log.i(Aura.TAG, "missed_calls_card ${card.reason} : ${card.groups.size} groupe(s)")  // jamais les numeros ni les noms
  }

  /** clé -> nombre d'appels annoncé par la notification encore affichée. */
  private fun postedCounts(ctx: Context): Map<String, Int> = try {
    ctx.getSystemService(NotificationManager::class.java).activeNotifications
      .filter { it.id == ID && it.tag?.startsWith(TAG_PREFIX) == true }
      .associate { it.tag.removePrefix(TAG_PREFIX) to it.notification.extras.getInt(EXTRA_COUNT, 1) }
  } catch (e: Exception) {
    emptyMap()
  }

  @Suppress("MissingPermission")
  private fun post(ctx: Context, g: MissedGroup, alert: Boolean, quiet: Boolean) {
    // l'écran verrouillé ne montre ni nom ni numéro
    val channel = if (quiet) Notifs.CH_INFO else Notifs.CH_MISSED
    val public = NotificationCompat.Builder(ctx, channel).setSmallIcon(R.drawable.ic_aura).setContentTitle("Appels manqués").build()
    val b = NotificationCompat.Builder(ctx, channel)
      .setSmallIcon(R.drawable.ic_aura)
      .setContentTitle(MissedLogic.title(g))
      .setContentText(MissedLogic.lastText(g).ifEmpty { "À rappeler" })
      .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_STATUS)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(public)
      .setGroup(GROUP)
      .setAutoCancel(true)
      .setOnlyAlertOnce(!alert)
      .setContentIntent(Notifs.launchAppIntent(ctx))
      .addExtras(Bundle().apply { putInt(EXTRA_COUNT, g.count) })
    if (quiet) b.setSilent(true)
    if (g.lastTs > 0) b.setWhen(g.lastTs * 1000).setShowWhen(true)
    if (g.dialable) b.addAction(0, "Rappeler", redialIntent(ctx, g.number))
    try {
      NotificationManagerCompat.from(ctx).notify(TAG_PREFIX + MissedLogic.key(g), ID, b.build())
    } catch (e: SecurityException) {
      // POST_NOTIFICATIONS retiree entre le test et l'appel
    }
  }

  @Suppress("MissingPermission")
  private fun postSummary(ctx: Context, calls: Int) {
    val b = NotificationCompat.Builder(ctx, Notifs.CH_INFO)
      .setSmallIcon(R.drawable.ic_aura)
      .setContentTitle(MissedLogic.callsLabel(calls))
      .setContentText("À rappeler")
      .setGroup(GROUP)
      .setGroupSummary(true)
      .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(NotificationCompat.Builder(ctx, Notifs.CH_INFO).setSmallIcon(R.drawable.ic_aura).setContentTitle("Appels manqués").build())
      .setAutoCancel(true)
      .setContentIntent(Notifs.launchAppIntent(ctx))
    try {
      NotificationManagerCompat.from(ctx).notify(TAG_SUMMARY, ID, b.build())
    } catch (e: SecurityException) {
      // idem
    }
  }

  /** Passe par RedialActivity : une activité lancée par la notification peut ouvrir le composeur (un récepteur ne le pourrait plus, Android 12+). */
  private fun redialIntent(ctx: Context, number: String): PendingIntent {
    val i = Intent(ctx, RedialActivity::class.java).setData(Uri.fromParts("redial", number, null)).putExtra(RedialActivity.EXTRA_NUMBER, number)
    return PendingIntent.getActivity(ctx, number.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }
}

/**
 * « Rappeler » : ouvre le composeur prérempli (`ACTION_DIAL tel:`), jamais d'appel direct. Le numéro est revalidé ici, par la
 * même regex stricte que celle de la carte, AVANT de fabriquer l'URI. Aucune vue : l'activité se ferme tout de suite.
 */
class RedialActivity : Activity() {
  companion object {
    const val EXTRA_NUMBER = "number"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val number = intent.getStringExtra(EXTRA_NUMBER) ?: ""
    if (MissedLogic.dialable(number)) {
      Aura.init(this)
      Usage.count("notif.rappeler")
      try {
        startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      } catch (e: Exception) {
        Log.w(Aura.TAG, "composeur non ouvert", e)
      }
    }
    finish()
  }
}
