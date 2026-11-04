package dev.aura.mobile.device

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.time.ZoneId
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Live Update du HUD (1.9.0, 22/10, PROTOCOL.md §35) : ce qui est URGENT, une chose a la fois (HudLogic.live) :
 * alerte grave, rendez-vous dans moins de 30 min (compte a rebours), mission detachee (option). Android 16 :
 * `Notification.ProgressStyle` + demande de promotion = puce de la barre d'etat, ecran verrouille, Now Bar de One UI 8.
 * Silencieuse (canal CH_HUD_LIVE sans son ni vibration, ecran verrouille en PRIVATE, onlyAlertOnce), rien la nuit (23 h-7 h) ni en pause, « Vu » l'ecarte
 * jusqu'a ce que la situation change (autre alerte grave, autre rendez-vous). Distincte de LiveUpdate (tour de chat).
 */
object HudLive {
  private const val EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing"
  private const val P_DISMISSED = "hud_live_dismissed"
  /** Rendu du compte a rebours (barre de progression) pendant la fenetre d'un rendez-vous. */
  private const val TICK_MS = 60_000L

  @Volatile private var shownKey: String? = null
  private var next: ScheduledFuture<*>? = null

  private fun dismissed(): Set<String> = Aura.prefs.getStringSet(P_DISMISSED, emptySet()) ?: emptySet()

  /** « Vu » : ecarte cette situation (gardee parmi les 20 dernieres). */
  fun dismiss(c: Context, key: String) {
    Aura.init(c)
    val s = LinkedHashSet(dismissed())
    s += key
    while (s.size > 20) s.remove(s.first())
    Aura.prefs.edit().putStringSet(P_DISMISSED, s).apply()
    update(c)
  }

  @Synchronized
  fun update(c: Context) {
    Aura.init(c)
    next?.cancel(false)
    next = null
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val feed = HudStore.feed(c)
    val live = if (Pause.active()) null else HudLogic.live(feed, HudStore.at(c), now, zone, HudSurfaces.liveOpts(), dismissed())
    if (live == null) {
      if (shownKey != null) Notifs.cancel(c, Notifs.ID_HUD_LIVE)
      shownKey = null
    } else {
      try {
        post(c, live)
        shownKey = live.key
      } catch (e: Exception) {
        Log.w(Aura.TAG, "HUD Live Update non affichee", e)
      }
    }
    // prochain rendu : chaque minute pendant un rendez-vous, sinon a l'ouverture de la fenetre du prochain (sans nouveau flux)
    val delay = when {
      live?.kind == "rdv" -> TICK_MS
      else -> HudLogic.rdvs(feed, zone).map { it.startMs - HudLogic.RDV_LEAD_MS - now }.filter { it > 0 }.minOrNull()
        ?.coerceAtMost(6 * 3_600_000L)
    }
    if (delay != null) next = Aura.timer.schedule(Runnable { update(c) }, delay + 500, TimeUnit.MILLISECONDS)
  }

  private fun post(c: Context, l: HudLogic.Live) {
    val open = HudSurfaces.openIntent(c, 7200, l.open)
    val seen = Notifs.receiverIntent(c, 7201, ActionReceiver.ACTION_HUD_LIVE_SEEN, mapOf("key" to l.key))
    // ecran verrouille : jamais le nom d'un client ni le detail d'une alerte, seulement la nature et l'heure
    val publicTitle = when (l.kind) {
      "grave" -> "Aura · alerte grave"
      "rdv" -> "Rendez-vous à ${l.chip}"
      else -> "Aura travaille"
    }
    val nm = c.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 36) {
      val style = Notification.ProgressStyle()
      if (l.progress >= 0) style.setProgress(l.progress) else style.setProgressIndeterminate(true)
      val b = Notification.Builder(c, Notifs.CH_HUD_LIVE)
        .setSmallIcon(R.drawable.ic_aura)
        .setContentTitle(l.title)
        .setContentText(l.text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(if (l.kind == "rdv") Notification.CATEGORY_EVENT else Notification.CATEGORY_STATUS)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setPublicVersion(Notification.Builder(c, Notifs.CH_HUD_LIVE).setSmallIcon(R.drawable.ic_aura).setContentTitle(publicTitle).build())
        .setContentIntent(open)
        .setStyle(style)
        .setColorized(false)
        .addAction(Notification.Action.Builder(null, "Vu", seen).build())
      if (l.countdownTo > 0) {
        b.setWhen(l.countdownTo).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
      }
      b.setShortCriticalText(l.chip)
      if (LiveUpdate.canPromote()) b.extras.putBoolean(EXTRA_REQUEST_PROMOTED, true)
      nm.notify(Notifs.ID_HUD_LIVE, b.build())
      return
    }
    val b = NotificationCompat.Builder(c, Notifs.CH_HUD_LIVE)
      .setSmallIcon(R.drawable.ic_aura)
      .setContentTitle(l.title)
      .setContentText(l.text)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setCategory(if (l.kind == "rdv") NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_STATUS)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(NotificationCompat.Builder(c, Notifs.CH_HUD_LIVE).setSmallIcon(R.drawable.ic_aura).setContentTitle(publicTitle).build())
      .setContentIntent(open)
      .addAction(0, "Vu", seen)
    if (l.progress >= 0) b.setProgress(100, l.progress, false) else b.setProgress(0, 0, true)
    if (l.countdownTo > 0) b.setWhen(l.countdownTo).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
    Notifs.post(c, Notifs.ID_HUD_LIVE, b)
  }
}
