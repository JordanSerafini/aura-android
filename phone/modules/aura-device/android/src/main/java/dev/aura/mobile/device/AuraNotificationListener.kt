package dev.aura.mobile.device

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Acces aux notifications : lecture des notifications actives, reponse (RemoteInput), fermeture,
 * ouverture. Les notifications sont lues a la demande (toujours exactes), pas copiees.
 */
class AuraNotificationListener : NotificationListenerService() {

  override fun onListenerConnected() {
    Aura.init(this)
    instance = this
    Aura.bridge.capsChanged()
  }

  override fun onListenerDisconnected() {
    instance = null
    Aura.bridge.capsChanged()
    try { requestRebind(ComponentName(this, AuraNotificationListener::class.java)) } catch (e: Exception) { /* API < 24 */ }
  }

  // Declencheur (PROTOCOL.md §9.1) : seulement les apps choisies par l'utilisateur, titre et texte tronques.
  // Rien n'est lu ni copie pour les autres ; PhoneEvents filtre avant de toucher au contenu.
  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    if (sbn == null) return
    try {
      PhoneEvents.onNotification(sbn)
    } catch (e: Exception) {
      Log.w(Aura.TAG, "declencheur notification", e)
    }
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

  // Notification retiree avec sa raison (03/10, PROTOCOL.md §19.3) : ouverte, balayee, annulee par l'app...
  // Android appelle cette surcharge (API 26+) a la place de la precedente.
  override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
    if (sbn == null) return
    try {
      PhoneEvents.onNotificationRemoved(sbn, reason, sbn.notification.channelId)
    } catch (e: Exception) {
      Log.w(Aura.TAG, "notification retiree non relayee", e)
    }
  }

  data class Info(val sbn: StatusBarNotification, val app: String, val label: String, val title: String, val text: String,
                  val conversation: String?, val replyAction: Notification.Action?, val messages: List<String>,
                  val channelName: String? = null) {
    fun toJson(): JSONObject = JSONObject()
      .put("key", sbn.key)
      .put("app", app)
      .put("app_name", label)
      .put("title", title)
      .put("text", text.take(1000))
      .put("conversation", conversation ?: JSONObject.NULL)
      .put("can_reply", replyAction != null)
      .put("messages", JSONArray(messages.takeLast(5)))
      .put("ts", sbn.postTime)
      // 03/10 (PROTOCOL.md §19.3) : effacable ou permanente, categorie Android, canal ; absents d'une APK plus ancienne
      .put("clearable", sbn.isClearable)
      .put("ongoing", (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
      .put("category", NotifRemoval.category(sbn.notification.category) ?: JSONObject.NULL)
      .put("channel", sbn.notification.channelId?.take(80) ?: JSONObject.NULL)
      .put("channel_name", channelName?.take(80) ?: JSONObject.NULL)
  }

  companion object {
    @Volatile var instance: AuraNotificationListener? = null

    fun service(): AuraNotificationListener {
      Perms.require("notification_listener", Perms.notifListener())
      return instance ?: throw ActionError("unsupported", "Accès aux notifications accordé mais service pas encore lié : réessaie dans quelques secondes")
    }

    private fun appLabel(pkg: String): String = try {
      val pm = Aura.app.packageManager
      pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
      pkg
    }

    fun info(sbn: StatusBarNotification): Info {
      val n = sbn.notification
      val ex: Bundle = n.extras
      val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
      val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
      val conv = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
      val messages = mutableListOf<String>()
      @Suppress("DEPRECATION")
      val raw = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
      raw?.forEach { p ->
        (p as? Bundle)?.let { b ->
          val who = b.getCharSequence("sender")?.toString()
          val t = b.getCharSequence("text")?.toString() ?: return@let
          messages += if (who != null) "$who : $t" else t
        }
      }
      val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
      return Info(sbn, sbn.packageName, appLabel(sbn.packageName), title, text, conv, reply, messages, channelName(sbn))
    }

    /** Nom lisible du canal (« Messages », « Promotions »...) d'apres le classement courant ; null s'il est illisible. */
    private fun channelName(sbn: StatusBarNotification): String? = try {
      val ranking = Ranking()
      val map = instance?.currentRanking
      if (map != null && map.getRanking(sbn.key, ranking)) ranking.channel?.name?.toString() else null
    } catch (e: Exception) {
      null
    }

    /** Notifications actives utiles (hors Aura, hors resumes de groupe), plus recentes d'abord. */
    fun active(): List<Info> {
      val svc = service()
      val list = try { svc.activeNotifications } catch (e: Exception) { null } ?: return emptyList()
      return list
        .filter { it.packageName != Aura.app.packageName && (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
        .sortedByDescending { it.postTime }
        .map { info(it) }
    }

    fun find(key: String): StatusBarNotification {
      val svc = service()
      return svc.activeNotifications?.firstOrNull { it.key == key }
        ?: throw ActionError("not_found", "Notification introuvable (déjà fermée ?)")
    }

    fun reply(sbn: StatusBarNotification, text: String) {
      val action = info(sbn).replyAction ?: throw ActionError("unsupported", "Cette notification ne propose pas de réponse")
      val inputs = action.remoteInputs.filter { it.allowFreeFormInput }.toTypedArray()
      val fill = Intent()
      val results = Bundle()
      inputs.forEach { results.putCharSequence(it.resultKey, text) }
      RemoteInput.addResultsToIntent(inputs, fill, results)
      Launcher.sendPending(action.actionIntent, fill)
    }

    fun dismiss(key: String) {
      find(key)
      service().cancelNotification(key)
    }

    fun open(key: String) {
      val sbn = find(key)
      val pi = sbn.notification.contentIntent ?: throw ActionError("unsupported", "Cette notification ne s'ouvre pas")
      Launcher.sendPending(pi)
    }
  }
}
