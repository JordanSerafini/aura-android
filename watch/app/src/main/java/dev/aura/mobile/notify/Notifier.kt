package dev.aura.mobile.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.aura.mobile.R
import dev.aura.mobile.health.Perms
import dev.aura.mobile.data.PendingConfirm
import dev.aura.mobile.ui.ConfirmActivity
import dev.aura.mobile.ui.ConfirmReceiver
import dev.aura.mobile.ui.MainActivity

object Notifier {
  const val CHANNEL_REPLY = "aura_reply"
  const val CHANNEL_NOTIFY = "aura_notify"
  const val CHANNEL_CONFIRM = "aura_confirm"
  const val CHANNEL_CALL = "aura_call"

  private const val ID_REPLY = 1
  private const val ID_NOTIFY_BASE = 1000
  private const val ID_CALL_CARD = 3000

  fun createChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    nm.createNotificationChannels(
      listOf(
        NotificationChannel(CHANNEL_REPLY, "Réponses d'Aura", NotificationManager.IMPORTANCE_HIGH).apply {
          description = "Réponse finale reçue quand l'app est fermée"
          enableVibration(true)
        },
        NotificationChannel(CHANNEL_NOTIFY, "Notifications d'Aura", NotificationManager.IMPORTANCE_HIGH).apply {
          description = "Notifications envoyées par Aura depuis le PC"
          enableVibration(true)
        },
        NotificationChannel(CHANNEL_CALL, "Fiche d'appel", NotificationManager.IMPORTANCE_HIGH).apply {
          description = "Qui t'appelle : la fiche client d'Aura pendant que le téléphone sonne"
          enableVibration(true)
        },
        NotificationChannel(CHANNEL_CONFIRM, "Confirmations", NotificationManager.IMPORTANCE_HIGH).apply {
          description = "Demandes d'accord avant une action"
          enableVibration(true)
        },
      ),
    )
  }

  fun canNotify(context: Context): Boolean =
    Perms.granted(context, Perms.NOTIFICATIONS) && NotificationManagerCompat.from(context).areNotificationsEnabled()

  private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    0,
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )

  @Suppress("MissingPermission")
  fun showReply(context: Context, text: String): Boolean {
    if (!canNotify(context)) return false
    val n = NotificationCompat.Builder(context, CHANNEL_REPLY)
      .setSmallIcon(R.drawable.ic_aura)
      .setContentTitle("Aura")
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .setContentIntent(openApp(context))
      .setAutoCancel(true)
      .build()
    NotificationManagerCompat.from(context).notify(ID_REPLY, n)
    return true
  }

  fun cancelReply(context: Context) = NotificationManagerCompat.from(context).cancel(ID_REPLY)

  @Suppress("MissingPermission")
  fun showNotify(context: Context, title: String, text: String, silent: Boolean): Boolean {
    if (!canNotify(context)) return false
    val n = NotificationCompat.Builder(context, CHANNEL_NOTIFY)
      .setSmallIcon(R.drawable.ic_aura)
      .setContentTitle(title.ifBlank { "Aura" })
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setSilent(silent)
      .setContentIntent(openApp(context))
      .setAutoCancel(true)
      .build()
    NotificationManagerCompat.from(context).notify(ID_NOTIFY_BASE + (System.currentTimeMillis() % 1000).toInt(), n)
    return true
  }

  /** Fiche d'appel : texte seul, 5 lignes au plus ; s'efface seule (l'appel est fini ou decroche). */
  @Suppress("MissingPermission")
  fun showCallCard(context: Context, card: dev.aura.mobile.protocol.CallCard): Boolean {
    if (!canNotify(context)) return false
    val body = dev.aura.mobile.protocol.CallCardLogic.body(card)
    val n = NotificationCompat.Builder(context, CHANNEL_CALL)
      .setSmallIcon(R.drawable.ic_aura)
      .setContentTitle(card.title)
      .setContentText(dev.aura.mobile.protocol.CallCardLogic.summary(card))
      .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifEmpty { "Appel entrant" }))
      .setSubText(card.number)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_STATUS)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setOnlyAlertOnce(true)
      .setAutoCancel(true)
      .setTimeoutAfter(3 * 60_000L)
      .setContentIntent(openApp(context))
      .build()
    NotificationManagerCompat.from(context).notify("call_card", ID_CALL_CARD, n)
    return true
  }

  private fun confirmNotificationId(actionId: String) = 2000 + (actionId.hashCode() and 0xFFFF)

  /**
   * Notification plein écran pour une confirmation : lance [ConfirmActivity] même écran éteint
   * (le démarrage direct d'activité depuis l'arrière-plan est bloqué par Android), avec deux actions en secours.
   */
  @Suppress("MissingPermission")
  fun showConfirm(context: Context, pending: PendingConfirm): Boolean {
    if (!canNotify(context)) return false
    val request = pending.request
    val code = confirmNotificationId(request.actionId)
    val full = PendingIntent.getActivity(
      context,
      code,
      ConfirmActivity.intent(context, pending),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    fun action(ok: Boolean, label: String) = NotificationCompat.Action.Builder(
      if (ok) R.drawable.ic_check else R.drawable.ic_close,
      label,
      PendingIntent.getBroadcast(
        context,
        code * 2 + if (ok) 1 else 0,
        ConfirmReceiver.intent(context, request.actionId, ok),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      ),
    ).build()
    val n = NotificationCompat.Builder(context, CHANNEL_CONFIRM)
      .setSmallIcon(R.drawable.ic_aura)
      .setContentTitle("Aura demande ton accord")
      .setContentText(request.summary)
      .setStyle(NotificationCompat.BigTextStyle().bigText(request.summary))
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setCategory(NotificationCompat.CATEGORY_REMINDER)
      .setFullScreenIntent(full, true)
      .setContentIntent(full)
      .addAction(action(true, "Oui"))
      .addAction(action(false, "Non"))
      .setTimeoutAfter(request.timeoutS.coerceAtLeast(1) * 1000L)
      .setOngoing(true)
      .build()
    NotificationManagerCompat.from(context).notify(code, n)
    return true
  }

  fun cancelConfirm(context: Context, actionId: String) =
    NotificationManagerCompat.from(context).cancel(confirmNotificationId(actionId))
}
