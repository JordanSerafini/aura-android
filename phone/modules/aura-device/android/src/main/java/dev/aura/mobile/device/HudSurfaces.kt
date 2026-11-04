package dev.aura.mobile.device

import android.app.Activity
import android.app.PendingIntent
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.TileService
import android.util.Log
import org.json.JSONObject
import java.time.ZoneId

/**
 * HUD hors de l'ecran d'accueil (1.9.0, PROTOCOL.md §35). Le flux `hud_feed` du widget (§33) alimente aussi :
 *  - la notification persistante du service (AuraService) : mini-HUD dans le volet ;
 *  - une Live Update Android 16 (HudLive) : alerte grave, rendez-vous dans moins de 30 min, mission (option) ;
 *    puce de la barre d'etat, ecran verrouille et Now Bar de One UI 8 si la promotion est accordee ;
 *  - la tuile Reglages rapides « Aura HUD » (HudTileService) ;
 *  - la montre (champ `hud` de `/aura/status`, tuile et complication « Aura HUD » de la 0.3.0).
 * Reglages dans Appareil → HUD hors de l'ecran d'accueil (settingsJson / setSettings). L'abonnement au flux part si au
 * moins une surface en veut (avant : seulement si un widget etait pose).
 */
object HudSurfaces {
  private const val P_NOTIF = "hud_notif"
  private const val P_LIVE_GRAVE = "hud_live_grave"
  private const val P_LIVE_RDV = "hud_live_rdv"
  private const val P_LIVE_MISSIONS = "hud_live_missions"
  private const val P_WATCH = "hud_watch"
  private const val P_TILE = "hud_tile_added"
  private const val P_PREVIEW_AT = "hud_preview_at"
  /** Apercu genere du selecteur de widgets (Android 15+) : le systeme limite les appels, une fois par 12 h suffit. */
  private const val PREVIEW_EVERY_MS = 12 * 3_600_000L

  @Volatile private var lastNotifKey: String? = null

  private fun on(key: String, def: Boolean) = Aura.prefs.getBoolean(key, def)

  fun notifOn() = on(P_NOTIF, true)
  fun watchOn() = on(P_WATCH, true)
  fun liveOpts() = HudLogic.LiveOpts(on(P_LIVE_GRAVE, true), on(P_LIVE_RDV, true), on(P_LIVE_MISSIONS, false))

  /** Une surface au moins veut le flux ; `widgetGone` : appele depuis onDisabled (le dernier widget part). */
  fun wanted(c: Context, widgetGone: Boolean = false): Boolean {
    Aura.init(c)
    val o = liveOpts()
    return (!widgetGone && AuraHudWidget.hasWidgets(c)) || notifOn() || o.grave || o.rdv || o.missions || on(P_TILE, false) ||
      (watchOn() && Aura.watch.connected())
  }

  fun brief(c: Context, nowMs: Long = System.currentTimeMillis()): HudLogic.Brief =
    HudLogic.brief(HudStore.feed(c), HudStore.at(c), nowMs, ZoneId.systemDefault())

  /** Nouveau flux (bridge, repli HTTP, reponse d'une action) : chaque surface se met a jour, silencieusement. */
  fun onFeed(c: Context) {
    safe("notification") { refreshNotification(c) }
    safe("live") { HudLive.update(c) }
    safe("tuile") { HudTileService.requestRefresh(c) }
    safe("montre") { WatchStatus.push() }
    safe("apercu") { maybePreview(c) }
  }

  private inline fun safe(what: String, block: () -> Unit) {
    try { block() } catch (e: Exception) { Log.w(Aura.TAG, "HUD $what : mise a jour en echec", e) }
  }

  /** La notification du service ne se repost que si son contenu change (pas de rafraichissement a vide). */
  fun refreshNotification(c: Context, force: Boolean = false) {
    val b = brief(c)
    val key = if (notifOn()) "${b.title}|${b.text}|${b.lines}" else "off"
    if (!force && key == lastNotifKey) return
    lastNotifKey = key
    AuraService.refreshNotification()
  }

  /** Apercu genere (Android 15+) : le selecteur de widgets montre le vrai HUD, pas un gabarit vide. */
  private fun maybePreview(c: Context) {
    if (Build.VERSION.SDK_INT < 35) return
    val now = System.currentTimeMillis()
    if (now - Aura.prefs.getLong(P_PREVIEW_AT, 0L) < PREVIEW_EVERY_MS) return
    val m = AppWidgetManager.getInstance(c) ?: return
    val ok = m.setWidgetPreview(ComponentName(c, AuraHudWidget::class.java), AppWidgetProviderInfoCompat.HOME,
      AuraHudWidget.views(c, HudLogic.Size.MEDIUM, 990, now, 2))
    if (ok) Aura.prefs.edit().putLong(P_PREVIEW_AT, now).apply()
  }

  // ─── Reglages (ecran Appareil de l'app) ───────────────────────────────────

  fun settingsJson(c: Context): JSONObject {
    Aura.init(c)
    val o = liveOpts()
    val b = brief(c)
    return JSONObject()
      .put("notif", notifOn())
      .put("live_grave", o.grave)
      .put("live_rdv", o.rdv)
      .put("live_missions", o.missions)
      .put("watch", watchOn())
      .put("tile_added", on(P_TILE, false))
      .put("widgets", AuraHudWidget.ids(c).size)
      .put("can_promote", LiveUpdate.canPromote())
      .put("android", Build.VERSION.SDK_INT)
      .put("watch_connected", try { Aura.watch.connected() } catch (e: Exception) { false })
      .put("feed_at", HudStore.at(c))
      .put("summary", HudLogic.tileSubtitle(b))
  }

  fun setSettings(c: Context, json: String): String {
    Aura.init(c)
    val p = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
    val e = Aura.prefs.edit()
    mapOf("notif" to P_NOTIF, "live_grave" to P_LIVE_GRAVE, "live_rdv" to P_LIVE_RDV, "live_missions" to P_LIVE_MISSIONS,
      "watch" to P_WATCH).forEach { (k, pref) -> if (p.has(k)) e.putBoolean(pref, p.optBoolean(k)) }
    e.apply()
    AuraHudWidget.resubscribe(c)
    refreshNotification(c, force = true)
    onFeed(c)
    return settingsJson(c).toString()
  }

  fun tileAdded(c: Context, added: Boolean) {
    Aura.init(c)
    Aura.prefs.edit().putBoolean(P_TILE, added).apply()
    AuraHudWidget.resubscribe(c)
  }

  /** « Poser le widget » : le lanceur propose de l'ajouter (One UI le prend en charge). */
  fun pinWidget(c: Context): String {
    val m = AppWidgetManager.getInstance(c) ?: return "indisponible"
    if (!m.isRequestPinAppWidgetSupported) return "non pris en charge par le lanceur"
    return if (m.requestPinAppWidget(ComponentName(c, AuraHudWidget::class.java), null, null)) "proposé" else "refusé"
  }

  /** « Ajouter la tuile » (Android 13+) : la boite de dialogue du systeme, sans passer par le crayon des Reglages rapides. */
  fun addTile(activity: Activity?): String {
    if (activity == null || Build.VERSION.SDK_INT < 33) return "Android 13 requis"
    val sbm = activity.getSystemService(StatusBarManager::class.java) ?: return "indisponible"
    sbm.requestAddTileService(ComponentName(activity, HudTileService::class.java), "Aura HUD",
      Icon.createWithResource(activity, R.drawable.ic_aura), activity.mainExecutor) { r ->
      if (r == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || r == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
        tileAdded(activity, true)
      }
    }
    return "proposé"
  }

  /** Ouvre l'app au bon endroit depuis une surface (notification, tuile). */
  fun openIntent(c: Context, code: Int, uri: String): PendingIntent {
    val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).setClassName(c, "dev.aura.mobile.MainActivity")
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }
}

/** Categorie d'apercu du selecteur (AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN), nommee ici pour la lisibilite. */
private object AppWidgetProviderInfoCompat {
  const val HOME = android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
}

/** Tuile Reglages rapides « Aura HUD » : etat du HUD en sous-titre (« 15 alertes · 3 à traiter ») ; toucher = le Hub. */
class HudTileService : TileService() {
  override fun onTileAdded() = HudSurfaces.tileAdded(this, true)

  override fun onTileRemoved() = HudSurfaces.tileAdded(this, false)

  override fun onStartListening() {
    Aura.init(this)
    val t = qsTile ?: return
    val b = HudSurfaces.brief(this)
    t.state = if (b.level == HudLogic.Level.GRAVE || b.level == HudLogic.Level.ALERTE || b.todo > 0) {
      android.service.quicksettings.Tile.STATE_ACTIVE
    } else {
      android.service.quicksettings.Tile.STATE_INACTIVE
    }
    t.label = "Aura HUD"
    val sub = HudLogic.tileSubtitle(b)
    if (Build.VERSION.SDK_INT >= 29) t.subtitle = sub
    t.contentDescription = "Aura HUD : $sub"
    t.updateTile()
  }

  override fun onClick() {
    Aura.init(this)
    Usage.count("tuile.hud")
    val pi = HudSurfaces.openIntent(this, 7100, "aura://hub")
    try {
      if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(pi) else {
        @Suppress("DEPRECATION") startActivityAndCollapse(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("aura://hub"))
          .setClassName(this, "dev.aura.mobile.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
    } catch (e: Exception) {
      Log.w(Aura.TAG, "tuile HUD : ouverture", e)
    }
  }

  companion object {
    fun requestRefresh(c: Context) {
      try { requestListeningState(c, ComponentName(c, HudTileService::class.java)) } catch (e: Exception) { /* tuile absente */ }
    }
  }
}
