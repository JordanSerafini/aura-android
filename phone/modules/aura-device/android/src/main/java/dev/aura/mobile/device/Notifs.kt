package dev.aura.mobile.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifs {
  const val CH_SERVICE = "aura_service"
  const val CH_CONFIRM = "aura_confirm"
  const val CH_FIND = "aura_find"
  const val CH_OPEN = "aura_open"
  // Notifications proactives du bridge (message WS `notify`) : jusqu'au 27/09 le service les
  // ignorait, elles n'apparaissaient que dans la WebView ouverte (aucun abonnement Web Push).
  const val CH_ALERT = "aura_alert"
  const val CH_INFO = "aura_info"
  // Live Update (LiveUpdate.kt) : importance DEFAULT sans son ni vibration. Android 16 refuse de
  // promouvoir une notification d'un canal IMPORTANCE_MIN, et LOW la cache de l'ecran verrouille sur One UI.
  const val CH_LIVE = "aura_live"
  // Carte d'appel (CallCards.kt) : fiche client a la sonnerie, heads-up sans son ni vibration (le telephone sonne deja)
  const val CH_CALL = "aura_call"
  // Appels manques regroupes (MissedCalls.kt), fin de tache (TaskDone.kt), temps a valider (TimeProposals.kt) : 01/10 soir
  const val CH_MISSED = "aura_missed"
  const val CH_DONE = "aura_done"
  const val CH_TIME = "aura_time"

  const val ID_SERVICE = 1001
  const val ID_FIND = 1002
  const val ID_LIVE = 1003
  private var nextId = 2000

  fun createChannels(ctx: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Connexion Aura", NotificationManager.IMPORTANCE_MIN).apply {
      description = "Notification permanente du service (obligatoire pour rester connecté app fermée)"
      setShowBadge(false)
    })
    nm.createNotificationChannel(NotificationChannel(CH_CONFIRM, "Confirmations Aura", NotificationManager.IMPORTANCE_HIGH).apply {
      description = "Aura demande ton accord avant d'agir (SMS, appel, message…)"
      enableVibration(true)
    })
    nm.createNotificationChannel(NotificationChannel(CH_FIND, "Trouver mon téléphone", NotificationManager.IMPORTANCE_HIGH).apply {
      setSound(null, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
      enableVibration(true)
    })
    nm.createNotificationChannel(NotificationChannel(CH_OPEN, "Ouvertures demandées par Aura", NotificationManager.IMPORTANCE_HIGH).apply {
      description = "Quand Android interdit d'ouvrir une appli en arrière-plan, Aura te propose de toucher pour l'ouvrir"
    })
    nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Alertes Aura", NotificationManager.IMPORTANCE_HIGH).apply {
      description = "Problème à traiter : PC qui sature, service en panne… (sévérité 2 et plus)"
      enableVibration(true)
    })
    nm.createNotificationChannel(NotificationChannel(CH_LIVE, "Aura en direct", NotificationManager.IMPORTANCE_DEFAULT).apply {
      description = "Pendant qu'Aura répond : étape en cours dans la barre d'état et sur l'écran verrouillé"
      setSound(null, null)
      enableVibration(false)
      setShowBadge(false)
      lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
    })
    nm.createNotificationChannel(NotificationChannel(CH_CALL, "Fiche d'appel", NotificationManager.IMPORTANCE_HIGH).apply {
      description = "Qui t'appelle : la fiche client d'Aura s'affiche pendant que le téléphone sonne"
      setSound(null, null)
      enableVibration(false)
      setShowBadge(false)
    })
    nm.createNotificationChannel(NotificationChannel(CH_MISSED, "Appels manqués", NotificationManager.IMPORTANCE_HIGH).apply {
      description = "Les numéros qui t'ont appelé sans réponse, avec « Rappeler » (ouvre le composeur)"
      enableVibration(true)
    })
    nm.createNotificationChannel(NotificationChannel(CH_DONE, "Tâches terminées", NotificationManager.IMPORTANCE_HIGH).apply {
      description = "Aura a fini une tâche longue pendant que tu n'étais pas dans l'app"
    })
    nm.createNotificationChannel(NotificationChannel(CH_TIME, "Temps à valider", NotificationManager.IMPORTANCE_DEFAULT).apply {
      description = "Les temps non saisis qu'Aura a chiffrés (17 h), et le résultat de leur saisie"
      setShowBadge(false)
    })
    nm.createNotificationChannel(NotificationChannel(CH_INFO, "Infos Aura", NotificationManager.IMPORTANCE_LOW).apply {
      description = "Notifications courantes d'Aura, sans son"
      setShowBadge(false)
    })
  }

  fun newId(): Int = synchronized(this) { nextId++ }

  fun launchAppIntent(ctx: Context): PendingIntent? {
    val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(ctx, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }

  fun receiverIntent(ctx: Context, reqCode: Int, action: String, extras: Map<String, String> = emptyMap()): PendingIntent {
    val i = Intent(ctx, ActionReceiver::class.java).setAction(action)
    extras.forEach { (k, v) -> i.putExtra(k, v) }
    return PendingIntent.getBroadcast(ctx, reqCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }

  fun canPost(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

  @Suppress("MissingPermission")
  fun post(ctx: Context, id: Int, b: NotificationCompat.Builder) {
    if (!canPost(ctx)) return
    try {
      NotificationManagerCompat.from(ctx).notify(id, b.build())
    } catch (e: SecurityException) {
      // POST_NOTIFICATIONS retiree entre le test et l'appel
    }
  }

  fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)

  /** Message `notify` du bridge {id, text, category, severity, ts, detail?, mute_key?, thread?, quiet?}
   *  → notification Android. Sévérité ≥ 2 : alerte sonore en tête d'écran ; 1 : silencieuse ;
   *  `quiet` (« Moins de ça » côté bridge) : rien.
   *  Jusqu'au 28/09 : titre « Aura » seul, sans heure ni bouton, et UNE notif par catégorie (deux
   *  conseils d'agenda : le 2e effaçait le 1er). Désormais une par notif, sauf un incident qui se
   *  répète (`thread`, ex. pression mémoire) qui remplace la précédente ; toutes rangées dans un groupe.
   *  Boutons : l'action propre au type (Résoudre, Brouillon, Diagnostic…) ou « Demander à Aura »,
   *  puis Archiver et Moins de ça ; balayer = lu (commun à tous les postes, notif_state). */
  fun showBridgeNotify(ctx: Context, msg: org.json.JSONObject, silent: Boolean = false) {
    // fin de tache : le heads-up natif de `task_done` s'efface devant cette notification (FinDedup), meme « Moins de ça »
    // (quiet) : l'utilisateur a coupe ce type de notification, le natif ne doit pas la refaire par un autre chemin
    if (msg.optString("category") == "aura-fin") TaskDone.onBridgeFin(msg.str("conv"))
    if (msg.optBoolean("quiet", false)) return
    val text = msg.optString("text").trim()
    if (text.isEmpty()) return
    val severity = msg.optInt("severity", 1)
    val category = msg.optString("category", "notifications")
    // recap d'appels manques et temps a valider : le bridge envoie AUSSI un message structure (missed_calls_card,
    // time_proposals) que l'app transforme en notification avec ses boutons ; la notification texte ferait doublon
    if (BridgeLogic.coveredByCard(category, msg.str("mute_key"))) return
    val nid = msg.optString("id").takeIf { ID_RE.matches(it) }
    val muteKey = msg.optString("mute_key").takeIf { it.isNotEmpty() && it.length <= 200 }
    val alert = severity >= 2
    val first = text.lineSequence().first().take(80)
    val label = LABELS[category] ?: "Aura"
    val sev = when { severity >= 3 -> "🔴 "; alert -> "🟠 "; else -> "" }
    val detail = msg.optString("detail").trim()
    val big = if (detail.isNotEmpty() && detail != text) "$text\n\n$detail" else text
    val ts = msg.optDouble("ts", 0.0)
    val reminder = msg.optString("reminder").takeIf { ID_RE.matches(it) }
    // meme incident qui se repete : remplace ; sinon une notif par id (a defaut : par categorie, comme avant)
    val tag = when {
      reminder != null -> "rappel:$reminder"  // la relance « Toujours : » remplace la 1re sonnerie
      msg.has("thread") && muteKey != null -> "thread:$muteKey"
      nid != null -> "notif:$nid"
      else -> "cat:$category"
    }
    val reqBase = tag.hashCode()
    val b = NotificationCompat.Builder(ctx, if (alert) CH_ALERT else CH_INFO)
      .setSmallIcon(R.drawable.ic_aura)
      .setContentTitle("$sev$label")
      .setContentText(first)
      .setSubText(if (msg.has("thread")) "×${msg.optInt("thread")}" else null)
      .setStyle(NotificationCompat.BigTextStyle().bigText(big.take(1500)))
      .setPriority(if (alert) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
      .setCategory(if (alert) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
      .setGroup(GROUP_BRIDGE)
      .setAutoCancel(true)
      .setContentIntent(deepLink(ctx, reqBase, if (nid != null) "aura://notifs" else null))
    if (ts > 0) b.setWhen((ts * 1000).toLong()).setShowWhen(true)
    if (silent) b.setSilent(true)  // rattrapage d'une notif de plus de 30 min : pas de sonnerie en retard
    if (nid != null) remember(nid)
    if (nid != null && reminder != null) {
      // rappel : Fait / +1 h directement (reminder_act, mis en file hors ligne), puis la liste des rappels
      val extras = mapOf("id" to nid, "tag" to tag, "reminder" to reminder)
      b.addAction(0, "✅ Fait", receiverIntent(ctx, reqBase + 1, ActionReceiver.ACTION_REMINDER_DONE, extras))
      b.addAction(0, "+1 h", receiverIntent(ctx, reqBase + 2, ActionReceiver.ACTION_REMINDER_SNOOZE, extras))
      b.addAction(0, "⏰ Rappels", deepLink(ctx, reqBase + 3, "aura://notif/$nid/act"))
      b.setDeleteIntent(receiverIntent(ctx, reqBase + 4, ActionReceiver.ACTION_NOTIF_READ, mapOf("id" to nid)))
    } else if (nid != null) {
      val act = contextAction(text, category, muteKey)
      val number = if (act == ACT_CALL_BACK) callNumber(text) else null
      if (number != null) b.addAction(0, act, dialIntent(ctx, reqBase + 1, number))
      else if (act != null) b.addAction(0, act, deepLink(ctx, reqBase + 1, "aura://notif/$nid/act"))
      else b.addAction(0, "💬 Aura", deepLink(ctx, reqBase + 1, "aura://notif/$nid/ask"))
      b.addAction(0, "🗄 Archiver", receiverIntent(ctx, reqBase + 2, ActionReceiver.ACTION_NOTIF_ARCHIVE,
        mapOf("id" to nid, "tag" to tag)))
      if (muteKey != null && severity < 3) {  // un urgent ne se coupe jamais (URGENT_SEVERITY cote bridge)
        b.addAction(0, "🔕 Moins de ça", receiverIntent(ctx, reqBase + 3, ActionReceiver.ACTION_NOTIF_MUTE,
          mapOf("id" to nid, "tag" to tag, "key" to muteKey)))
      }
      b.setDeleteIntent(receiverIntent(ctx, reqBase + 4, ActionReceiver.ACTION_NOTIF_READ, mapOf("id" to nid)))
    }
    postTagged(ctx, tag, b)
    postGroupSummary(ctx)
  }

  /** Meme detection que notifActions() de la PWA (app.js) : le libelle du bouton, l'action part de la PWA.
   *  Sauf « Rappeler » (appel manque, natif seulement) : le numero s'ouvre dans le composeur, sans appel direct. */
  fun contextAction(text: String, category: String, muteKey: String?): String? = when {
    category == "aura-appel" && callNumber(text) != null -> ACT_CALL_BACK
    Regex("conflit d'agenda", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "🗓 Résoudre"
    Regex("attend ta réponse|✉️", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "✉️ Brouillon"
    Regex("promesse", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "⏰ Demain 9h"
    category == "memoire" || category == "aura-sante" || muteKey?.startsWith("digest:journald:") == true -> "🩺 Diagnostic"
    category == "night" -> "🌙 Décider"
    category == "aura-rappel" -> "⏰ Rappels"
    else -> null
  }

  const val ACT_CALL_BACK = "📞 Rappeler"

  // ACTION_DIAL : composeur prerempli, l'utilisateur appuie lui-meme (pas de permission CALL_PHONE)
  private fun dialIntent(ctx: Context, reqCode: Int, number: String): PendingIntent {
    val i = Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", number, null))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return PendingIntent.getActivity(ctx, reqCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }

  // ─── Rattrapage au welcome (`recent`) ────────────────────────────────────

  private const val PREF_LAST_TS = "notif_last_ts"
  private const val SHOWN_MAX = 200
  private val tsLock = Any()
  private val shown = LinkedHashSet<String>()

  private fun remember(id: String) = synchronized(shown) {
    shown.remove(id)
    shown.add(id)
    while (shown.size > SHOWN_MAX) shown.remove(shown.first())
  }

  private fun lastTs(): Double? = Aura.prefs.getString(PREF_LAST_TS, null)?.toDoubleOrNull()

  /** ts (s) de la derniere notif vue : `notify` en direct, puis ts max de `recent` au welcome. */
  fun seenTs(ts: Double) {
    if (ts <= 0) return
    synchronized(tsLock) {
      val last = lastTs()
      if (last == null || ts > last) Aura.prefs.edit().putString(PREF_LAST_TS, ts.toString()).apply()
    }
  }

  /** Notifications arrivees pendant la coupure : non lues, non archivees, 8 au plus, sans son au-dela de 30 min. */
  fun catchUp(ctx: Context, recent: org.json.JSONArray?) {
    val already = synchronized(shown) { shown.toMutableSet() }
    try {
      // encore affichee (processus redemarre depuis) : pas de doublon
      ctx.getSystemService(NotificationManager::class.java)?.activeNotifications
        ?.mapNotNull { it.tag?.removePrefix("notif:")?.takeIf { t -> t != it.tag } }?.let { already.addAll(it) }
    } catch (e: Exception) {
      // lecture des notifs actives indisponible : on s'en tient a la memoire
    }
    val r = synchronized(tsLock) {
      NotifCatchUp.select(recent, lastTs(), already, System.currentTimeMillis() / 1000.0).also {
        Aura.prefs.edit().putString(PREF_LAST_TS, it.lastTs.toString()).apply()
      }
    }
    r.show.forEachIndexed { i, n -> showBridgeNotify(ctx, n, silent = r.silent[i]) }
  }

  fun deepLink(ctx: Context, reqCode: Int, uri: String?): PendingIntent? {
    if (uri == null) return launchAppIntent(ctx)
    val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).setPackage(ctx.packageName)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(ctx, reqCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }

  @Suppress("MissingPermission")
  private fun postTagged(ctx: Context, tag: String, b: NotificationCompat.Builder) {
    if (!canPost(ctx)) return
    try {
      NotificationManagerCompat.from(ctx).notify(tag, ID_BRIDGE, b.build())
    } catch (e: SecurityException) {
      // POST_NOTIFICATIONS retiree entre le test et l'appel
    }
  }

  // resume du groupe : sans lui, Android ne regroupe qu'a partir de 4 notifs et a sa facon
  private fun postGroupSummary(ctx: Context) {
    val b = NotificationCompat.Builder(ctx, CH_INFO)
      .setSmallIcon(R.drawable.ic_aura)
      .setContentTitle("Aura")
      .setContentText("Notifications d'Aura")
      .setGroup(GROUP_BRIDGE)
      .setGroupSummary(true)
      .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
      .setAutoCancel(true)
      .setContentIntent(deepLink(ctx, ID_BRIDGE_SUMMARY, "aura://notifs"))
    post(ctx, ID_BRIDGE_SUMMARY, b)
  }

  fun cancelTagged(ctx: Context, tag: String) = NotificationManagerCompat.from(ctx).cancel(tag, ID_BRIDGE)

  /** notif_state / notif_mute vers le bridge ; hors ligne, rien n'est garde (la notif reste « non lue »). */
  fun sendNotifState(id: String, read: Boolean? = null, archived: Boolean? = null) {
    val o = org.json.JSONObject().put("type", "notif_state").put("ids", org.json.JSONArray().put(id))
    read?.let { o.put("read", it) }
    archived?.let { o.put("archived", it) }
    Aura.bridge.send(o)
  }
}

private val ID_RE = Regex("^[A-Za-z0-9_-]{1,64}$")
private const val GROUP_BRIDGE = "dev.aura.mobile.BRIDGE"
private const val ID_BRIDGE = 0x4000
private const val ID_BRIDGE_SUMMARY = 0x4001
// memes libelles que NOTIF_LABELS de la PWA (app.js)
private val LABELS = mapOf(
  "media" to "Média", "night" to "Nuit", "aura-agenda" to "Agenda", "aura-sante" to "Santé",
  "aura-rappel" to "Rappel", "memoire" to "Santé", "aura-conseil" to "Conseil", "aura" to "Aura",
  "notifications" to "AURA", "aura-appel" to "Appel", "aura-mail" to "Mail",
  "aura-fin" to "Tâche terminée", "aura-temps" to "Temps",
)

/** Boutons des notifications : Accepter / Refuser une confirmation, arreter la sonnerie. */
class ActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    Aura.init(context)
    when (intent.action) {
      ACTION_CONFIRM_OK, ACTION_CONFIRM_NO -> {
        val id = intent.getStringExtra("action_id") ?: return
        Aura.confirms.resolve(id, if (intent.action == ACTION_CONFIRM_OK) "accepted" else "refused", "notification")
      }
      ACTION_FIND_STOP -> FindPhone.stop()
      ACTION_NOTIF_READ -> intent.getStringExtra("id")?.let { Notifs.sendNotifState(it, read = true) }
      ACTION_NOTIF_ARCHIVE -> intent.getStringExtra("id")?.let {
        Notifs.sendNotifState(it, archived = true)
        intent.getStringExtra("tag")?.let { t -> Notifs.cancelTagged(context, t) }
      }
      ACTION_NOTIF_MUTE -> {
        val key = intent.getStringExtra("key") ?: return
        Aura.bridge.send(org.json.JSONObject().put("type", "notif_mute").put("key", key))
        intent.getStringExtra("id")?.let { Notifs.sendNotifState(it, read = true) }
        intent.getStringExtra("tag")?.let { t -> Notifs.cancelTagged(context, t) }
      }
      ACTION_REMINDER_DONE, ACTION_REMINDER_SNOOZE -> {
        val rid = intent.getStringExtra("reminder") ?: return
        val act = org.json.JSONObject().put("type", "reminder_act").put("reminder", rid)
        if (intent.action == ACTION_REMINDER_DONE) act.put("action", "done")
        else act.put("action", "snooze").put("minutes", 60)
        Aura.bridge.sendOrQueue(act)  // hors ligne : rejoue au prochain welcome
        intent.getStringExtra("id")?.let { Notifs.sendNotifState(it, read = true) }
        intent.getStringExtra("tag")?.let { t -> Notifs.cancelTagged(context, t) }
      }
      ACTION_PAUSE_TOGGLE -> Pause.toggle("notification")
      ACTION_LIMIT_CANCEL -> intent.getStringExtra("conv")?.let { conv ->
        if (!LimitBanner.cancel(conv)) {
          Aura.main.post { android.widget.Toast.makeText(context, "Aura est hors ligne : la reprise n'a pas pu être annulée", android.widget.Toast.LENGTH_LONG).show() }
        }
      }
      ACTION_COPY_TEXT -> intent.getStringExtra("text")?.let { TimeProposals.copyToClipboard(context, it) }
      ACTION_LIVE_CANCEL -> intent.getStringExtra("id")?.let {
        Aura.bridge.send(org.json.JSONObject().put("type", "cancel").put("id", it))
      }
    }
  }

  companion object {
    const val ACTION_CONFIRM_OK = "dev.aura.mobile.CONFIRM_OK"
    const val ACTION_CONFIRM_NO = "dev.aura.mobile.CONFIRM_NO"
    const val ACTION_FIND_STOP = "dev.aura.mobile.FIND_STOP"
    const val ACTION_LIVE_CANCEL = "dev.aura.mobile.LIVE_CANCEL"
    const val ACTION_PAUSE_TOGGLE = "dev.aura.mobile.PAUSE_TOGGLE"
    const val ACTION_LIMIT_CANCEL = "dev.aura.mobile.LIMIT_CANCEL"
    const val ACTION_COPY_TEXT = "dev.aura.mobile.COPY_TEXT"
    const val ACTION_NOTIF_READ = "dev.aura.mobile.NOTIF_READ"
    const val ACTION_NOTIF_ARCHIVE = "dev.aura.mobile.NOTIF_ARCHIVE"
    const val ACTION_NOTIF_MUTE = "dev.aura.mobile.NOTIF_MUTE"
    const val ACTION_REMINDER_DONE = "dev.aura.mobile.REMINDER_DONE"
    const val ACTION_REMINDER_SNOOZE = "dev.aura.mobile.REMINDER_SNOOZE"
  }
}
