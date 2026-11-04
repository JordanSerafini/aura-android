package dev.aura.mobile.device

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Widget « Aura HUD » : le HUD plein ecran du serveur (celui qu'il affiche sur un ecran du PC), en widget d'ecran d'accueil
 * redimensionnable.
 *  - petit (2x2) : noyau (alertes, couleur de gravite), prochain rendez-vous, heure de fraicheur ;
 *  - moyen (4x2) : + 3 lignes « À traiter » les plus urgentes (bouton FAIT / TRAITÉ) ;
 *  - grand (4x4 et plus) : liste defilante ALERTES / PLANNING / À TRAITER / SYSTÈME / SUPERVISEUR / EN COURS / BLOC-NOTES.
 * 1.9.0 : gabarits responsifs (Android 12+), 1 a 3 lignes selon la hauteur, barre de sante,
 * horloge, mode, ligne « Aura travaille », micro (Talk), rendez-vous en cours, boutons de 48 dp decrits pour TalkBack.
 * Le meme flux alimente les surfaces hors de l'ecran d'accueil (HudSurfaces.kt).
 *
 * RemoteViews classiques (comme AuraWidget) plutot que Glance : pas de Compose dans ce module, et Glance ne fait que
 * traduire en RemoteViews. Liste du grand format : RemoteCollectionItems (API 31+, sans RemoteViewsService) ; en dessous
 * d'Android 12 le grand format retombe sur le moyen.
 *
 * Donnees (PROTOCOL.md §33) : poussees par le bridge (`hud_feed`) sur la WebSocket du service natif (abonnement `hud_sub`
 * au welcome tant qu'un widget est pose) ; repli GET /app/hud/feed (jeton de l'appareil) quand le systeme rappelle onUpdate
 * (30 min) et que le flux a plus de 15 min, ou sur ↻. Actions : POST /app/hud/act ; les fortes (RÉPARE, MASQUER 24H, ÉCARTER)
 * demandent un 2e appui dans les 6 s (HudLogic.tap). Retour immediat (« … », puis le message du bridge), puis la verite
 * du flux renvoye avec la reponse.
 */
class AuraHudWidget : AppWidgetProvider() {
  override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
    render(context, manager, ids)
    if (System.currentTimeMillis() - HudStore.at(context) > HudLogic.REFETCH_MS) fetchAsync(context)
  }

  override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
    render(context, manager, intArrayOf(id))
  }

  override fun onEnabled(context: Context) = subscribe(context, true)

  override fun onDisabled(context: Context) = subscribe(context, HudSurfaces.wanted(context, widgetGone = true))

  override fun onReceive(context: Context, intent: Intent) {
    Aura.init(context)
    when (intent.action) {
      ACT_REFRESH -> fetchAsync(context, force = true)
      ACT_TAP -> onTap(context, intent.getStringExtra(EXTRA_ACTION) ?: "", intent.getStringExtra(EXTRA_ID) ?: "")
      else -> super.onReceive(context, intent)
    }
  }

  /** Appui sur un bouton : decision (HudLogic.tap), retour visuel tout de suite, puis l'action en tache (goAsync). */
  private fun onTap(context: Context, action: String, id: String) {
    val now = System.currentTimeMillis()
    when (val t = HudLogic.tap(action, id, HudStore.armed(context), now)) {
      is HudLogic.Tap.Refuse -> { HudStore.toast(context, t.why); refresh(context); clearToastLater(context) }
      is HudLogic.Tap.Arm -> {
        HudStore.arm(context, t.key, t.until)
        refresh(context)
        // desarme tout seul : le libelle « CONFIRMER ? » ne doit pas rester affiche
        Aura.timer.schedule(Runnable { if (HudStore.armed(context)?.first == t.key) { HudStore.arm(context, null, 0); refresh(context) } },
          HudLogic.CONFIRM_MS + 200, TimeUnit.MILLISECONDS)
      }
      is HudLogic.Tap.Run -> {
        HudStore.arm(context, null, 0)
        HudStore.pending(context, HudLogic.armKey(t.action, t.id))
        refresh(context)
        val done = goAsync()
        Aura.pool.execute {
          try {
            val res = Http.act(context, t.action, t.id)
            HudStore.toast(context, res.optString("msg", if (res.optBoolean("ok")) "Fait" else "Échec"))
            res.optJSONObject("feed")?.let { HudStore.save(context, it) }
            Usage.count("widget_hud.${t.action}")
          } catch (e: Exception) {
            Log.w(Aura.TAG, "widget HUD : action ${t.action} en echec", e)
            HudStore.toast(context, "PC injoignable · rien fait")
          } finally {
            HudStore.pending(context, null)
            refresh(context)
            clearToastLater(context)
            done.finish()
          }
        }
      }
    }
  }

  private fun fetchAsync(context: Context, force: Boolean = false) {
    val done = goAsync()
    if (force) { HudStore.pending(context, "refresh"); refresh(context) }
    Aura.pool.execute {
      try {
        HudStore.save(context, Http.feed(context, force))
        if (force) HudStore.toast(context, null)
      } catch (e: Exception) {
        Log.i(Aura.TAG, "widget HUD : flux injoignable (${e.message})")
        if (force) HudStore.toast(context, "PC injoignable")
      } finally {
        if (force) HudStore.pending(context, null)
        refresh(context)
        if (force) clearToastLater(context)
        done.finish()
      }
    }
  }

  companion object {
    const val ACT_REFRESH = "dev.aura.mobile.HUD_REFRESH"
    const val ACT_TAP = "dev.aura.mobile.HUD_TAP"
    const val EXTRA_ACTION = "hud_action"
    const val EXTRA_ID = "hud_id"
    const val EXTRA_OPEN = "hud_open"
    private const val TOAST_MS = 8_000L

    fun ids(context: Context): IntArray {
      val m = AppWidgetManager.getInstance(context) ?: return IntArray(0)
      return m.getAppWidgetIds(ComponentName(context, AuraHudWidget::class.java))
    }

    /** Le message bref disparait seul : sans nouveau rendu, il restait affiche jusqu'au flux suivant (vu sur emulateur). */
    private fun clearToastLater(context: Context) {
      Aura.timer.schedule(Runnable { refresh(context) }, TOAST_MS + 300, TimeUnit.MILLISECONDS)
    }

    fun hasWidgets(context: Context): Boolean = ids(context).isNotEmpty()

    fun refresh(context: Context) {
      val m = AppWidgetManager.getInstance(context) ?: return
      val ids = ids(context)
      if (ids.isNotEmpty()) render(context, m, ids)
    }

    /** Flux pousse par le bridge (BridgeClient, message `hud_feed`). */
    fun onBridgeFeed(context: Context, msg: JSONObject) {
      val feed = msg.optJSONObject("feed") ?: return
      HudStore.save(context, feed)
      refresh(context)
    }

    /** Welcome du bridge : abonnement si un widget est pose (sinon rien ne part, le serveur ne calcule pas le flux). */
    fun onWelcome(context: Context) {
      if (HudSurfaces.wanted(context)) Aura.bridge.send(JSONObject().put("type", "hud_sub").put("on", true))
    }

    /** Abonnement au flux selon les surfaces actives (widget pose, notification, Live Update, tuile, montre). */
    fun resubscribe(context: Context) = subscribe(context, HudSurfaces.wanted(context))

    private fun subscribe(context: Context, on: Boolean) {
      try {
        Aura.init(context)
        Aura.bridge.send(JSONObject().put("type", "hud_sub").put("on", on))
      } catch (e: Exception) {
        Log.w(Aura.TAG, "widget HUD : abonnement non envoye", e)
      }
    }

    private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
      for (id in ids) {
        try {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 1.9.0 : gabarits responsifs, le lanceur prend le plus grand qui tient (portrait, paysage, grille One UI)
            val now = System.currentTimeMillis()
            val map = HudLogic.RESPONSIVE.associate { (wh, size, lines) ->
              SizeF(wh.first, wh.second) to views(context, size, id, now, lines)
            }
            manager.updateAppWidget(id, RemoteViews(map))
            continue
          }
          val o = manager.getAppWidgetOptions(id)
          // largeur/hauteur en dp : portrait = largeur min et hauteur max (doc AppWidgetManager)
          val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
          val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
          val (size, lines) = HudLogic.pick(w.toFloat(), h.toFloat())
          manager.updateAppWidget(id, views(context, size, id, lines = lines))
        } catch (e: Exception) {
          Log.w(Aura.TAG, "widget HUD : rendu en echec", e)
        }
      }
    }

    // ─── Rendu ───────────────────────────────────────────────────────────────

    private fun color(context: Context, t: HudLogic.Tone): Int = context.getColor(when (t) {
      HudLogic.Tone.VIOLET -> R.color.hud_violet
      HudLogic.Tone.GREEN -> R.color.hud_green
      HudLogic.Tone.AMBER -> R.color.hud_amber
      HudLogic.Tone.RED -> R.color.hud_red
      HudLogic.Tone.MUTED -> R.color.hud_muted
      HudLogic.Tone.CYAN -> R.color.hud_cyan
    })

    private fun coreBg(l: HudLogic.Level): Int = when (l) {
      HudLogic.Level.GRAVE -> R.drawable.hud_core_grave
      HudLogic.Level.ALERTE -> R.drawable.hud_core_alerte
      HudLogic.Level.OK -> R.drawable.hud_core_ok
      HudLogic.Level.INCONNU -> R.drawable.hud_core_inconnu
    }

    private fun openIntent(context: Context, code: Int, uri: String): PendingIntent {
      val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setClassName(context, "dev.aura.mobile.MainActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun tapIntent(context: Context, code: Int, action: String, id: String): PendingIntent {
      val i = Intent(context, AuraHudWidget::class.java).setAction(ACT_TAP).putExtra(EXTRA_ACTION, action).putExtra(EXTRA_ID, id)
        .setData(Uri.parse("aura-hud://tap/$code"))  // donnee distincte : un PendingIntent par bouton, pas un seul reutilise
      return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun refreshIntent(context: Context): PendingIntent {
      val i = Intent(context, AuraHudWidget::class.java).setAction(ACT_REFRESH)
      return PendingIntent.getBroadcast(context, 60, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Vue d'un format ; `widgetId` sert a distinguer les PendingIntent de deux widgets poses. Public : HudPreview. */
    fun views(context: Context, size: HudLogic.Size, widgetId: Int, nowMs: Long = System.currentTimeMillis(), lines: Int = 3): RemoteViews {
      val feed = HudStore.feed(context)
      val zone = ZoneId.systemDefault()
      val hd = HudLogic.header(feed, HudStore.at(context), nowMs, zone)
      val armed = HudStore.armed(context)
      val pending = HudStore.pending(context, nowMs)
      val toast = HudStore.toast(context, nowMs, TOAST_MS)
      val large = size == HudLogic.Size.LARGE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
      val layout = when {
        large -> R.layout.hud_widget_large
        size == HudLogic.Size.SMALL -> R.layout.hud_widget_small
        else -> R.layout.hud_widget_medium
      }
      val v = RemoteViews(context.packageName, layout)
      val base = widgetId * 100
      v.setTextViewText(R.id.hud_core, hd.core)
      v.setInt(R.id.hud_core, "setBackgroundResource", coreBg(hd.level))
      v.setContentDescription(R.id.hud_core, hd.coreA11y)
      v.setTextViewText(R.id.hud_next, hd.next)
      val freshTxt = if (hd.mode.isNotEmpty() && !hd.offline) "${hd.fresh} · ${hd.mode}" else hd.fresh
      v.setTextViewText(R.id.hud_fresh, toast ?: if (pending == "refresh") "Actualisation…" else freshTxt)
      v.setContentDescription(R.id.hud_fresh, if (hd.offline) hd.fresh else "Données reçues ${hd.fresh}")
      v.setInt(R.id.hud_health, "setBackgroundColor", color(context, hd.health))
      v.setTextColor(R.id.hud_fresh, color(context, if (toast != null) HudLogic.Tone.VIOLET else if (hd.offline) HudLogic.Tone.AMBER else HudLogic.Tone.MUTED))
      v.setTextViewText(R.id.hud_weather, hd.weather)
      v.setViewVisibility(R.id.hud_weather, if (hd.weather.isEmpty()) View.GONE else View.VISIBLE)
      v.setOnClickPendingIntent(R.id.hud_core, openIntent(context, base + 1, "aura://hub"))
      v.setOnClickPendingIntent(R.id.hud_head, openIntent(context, base + 2, "aura://view/actions"))
      if (size == HudLogic.Size.SMALL) return v

      v.setOnClickPendingIntent(R.id.hud_refresh, refreshIntent(context))
      v.setOnClickPendingIntent(R.id.hud_mic, openIntent(context, base + 4, DictateTileService.TALK_URI))
      v.setTextViewText(R.id.hud_status, hd.status)
      v.setViewVisibility(R.id.hud_status, if (hd.status.isEmpty()) View.GONE else View.VISIBLE)
      if (large) {
        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(2)
        for ((i, r) in HudLogic.rows(feed, nowMs, zone).withIndex()) items.addItem(i.toLong() + 1, row(context, r, armed, pending, nowMs))
        v.setRemoteAdapter(R.id.hud_list, items.build())
        // un seul gabarit par liste : une activite-relais (HudTapActivity) qui ouvre l'app ou renvoie l'appui au widget
        val tpl = Intent(context, HudTapActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        v.setPendingIntentTemplate(R.id.hud_list, PendingIntent.getActivity(context, base + 3, tpl,
          PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        return v
      }
      val todo = HudLogic.topTodo(feed, nowMs, zone, lines)
      val ids = listOf(Triple(R.id.hud_l1, R.id.hud_l1_t, R.id.hud_l1_b), Triple(R.id.hud_l2, R.id.hud_l2_t, R.id.hud_l2_b),
        Triple(R.id.hud_l3, R.id.hud_l3_t, R.id.hud_l3_b))
      for ((i, slot) in ids.withIndex()) {
        if (i >= lines) { v.setViewVisibility(slot.first, View.GONE); continue }
        val r = todo.getOrNull(i)
        val (box, txt, btn) = slot
        if (r == null) {
          v.setViewVisibility(box, if (i == 0) View.VISIBLE else View.GONE)
          if (i == 0) {
            v.setTextViewText(txt, if (feed == null) "En attente du PC…" else "Rien à traiter")
            v.setTextColor(txt, color(context, if (feed == null) HudLogic.Tone.MUTED else HudLogic.Tone.GREEN))
            v.setViewVisibility(btn, View.GONE)
          }
          continue
        }
        v.setViewVisibility(box, View.VISIBLE)
        v.setTextViewText(txt, "${r.title} · ${r.detail}")
        v.setTextColor(txt, if (r.tone == HudLogic.Tone.RED) color(context, HudLogic.Tone.RED) else context.getColor(R.color.hud_text))
        v.setOnClickPendingIntent(txt, openIntent(context, base + 10 + i, r.open))
        val b = r.buttons.firstOrNull { !it.strong }
        if (b == null) v.setViewVisibility(btn, View.GONE) else {
          v.setViewVisibility(btn, View.VISIBLE)
          v.setTextViewText(btn, HudLogic.label(b, armed, pending, nowMs))
          v.setTextColor(btn, color(context, if (armed?.first == HudLogic.armKey(b.action, b.id)) HudLogic.Tone.RED else b.tone))
          v.setContentDescription(btn, HudLogic.btnA11y(b, r.title, armed, pending, nowMs))
          v.setOnClickPendingIntent(btn, tapIntent(context, base + 20 + i, b.action, b.id))
        }
      }
      return v
    }

    /** Lignes du grand format (hors d'un hote de widgets, setRemoteAdapter est refuse : aperçu ou capture). */
    internal fun previewRows(context: Context, nowMs: Long = System.currentTimeMillis()): List<RemoteViews> =
      HudLogic.rows(HudStore.feed(context), nowMs, ZoneId.systemDefault())
        .map { row(context, it, HudStore.armed(context), HudStore.pending(context, nowMs), nowMs) }

    private fun row(context: Context, r: HudLogic.Row, armed: Pair<String, Long>?, pending: String?, nowMs: Long): RemoteViews {
      if (r.section) {
        return RemoteViews(context.packageName, R.layout.hud_row_section).apply {
          setTextViewText(R.id.hud_sec, r.title)
          setTextColor(R.id.hud_sec, color(context, r.tone))
        }
      }
      return RemoteViews(context.packageName, R.layout.hud_row).apply {
        setTextViewText(R.id.hud_row_title, r.title)
        setInt(R.id.hud_row_bar, "setBackgroundColor", color(context, r.tone))
        setTextViewText(R.id.hud_row_time, r.time)
        setViewVisibility(R.id.hud_row_time, if (r.time.isEmpty()) View.GONE else View.VISIBLE)
        setTextViewText(R.id.hud_row_detail, r.detail)
        setViewVisibility(R.id.hud_row_detail, if (r.detail.isEmpty()) View.GONE else View.VISIBLE)
        setOnClickFillInIntent(R.id.hud_row_main, Intent().putExtra(EXTRA_OPEN, r.open))
        val slots = listOf(R.id.hud_b1, R.id.hud_b2, R.id.hud_b3)
        setViewVisibility(R.id.hud_row_btns, if (r.buttons.isEmpty()) View.GONE else View.VISIBLE)
        for ((i, id) in slots.withIndex()) {
          val b = r.buttons.getOrNull(i)
          if (b == null) { setViewVisibility(id, View.GONE); continue }
          setViewVisibility(id, View.VISIBLE)
          setTextViewText(id, HudLogic.label(b, armed, pending, nowMs))
          setTextColor(id, color(context, if (armed?.first == HudLogic.armKey(b.action, b.id)) HudLogic.Tone.RED else b.tone))
          setContentDescription(id, HudLogic.btnA11y(b, r.title, armed, pending, nowMs))
          setOnClickFillInIntent(id, Intent().putExtra(EXTRA_ACTION, b.action).putExtra(EXTRA_ID, b.id))
        }
      }
    }
  }

  /** Appels HTTP au bridge, jeton de l'appareil en Bearer, sur la meme adresse que la WebSocket (deja filtree par BridgeUrl). */
  internal object Http {
    private val client by lazy {
      // callTimeout : un appui passe par goAsync (recepteur en arriere-plan, ~60 s au plus) ; 25 s par appel.
      // Pas de redirection suivie : le jeton ne part que vers l'adresse configuree.
      OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    }
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun bases(): List<String> = listOfNotNull(HudLogic.httpBase(Aura.url))

    private fun call(context: Context, path: String, body: JSONObject?): JSONObject {
      Aura.init(context)
      val token = Aura.token
      if (token.isEmpty()) throw IllegalStateException("aucun jeton appareil")
      var last: Exception? = null
      for (b in bases()) {
        try {
          val rb = Request.Builder().url(b + path).header("Authorization", "Bearer $token")
          val req = if (body == null) rb.get().build() else rb.post(body.toString().toRequestBody(JSON)).build()
          client.newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: ""
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            return JSONObject(txt)
          }
        } catch (e: Exception) {
          last = e
          if (e !is java.net.UnknownHostException && e !is java.net.ConnectException && e !is java.net.SocketTimeoutException) throw e
        }
      }
      throw last ?: IllegalStateException("bridge injoignable")
    }

    fun feed(context: Context, force: Boolean): JSONObject = call(context, "/app/hud/feed" + if (force) "?force=1" else "", null)

    fun act(context: Context, action: String, id: String): JSONObject = call(context, "/app/hud/act", HudLogic.actionBody(action, id))
  }
}

/** Etat du widget HUD (prefs) : dernier flux et son heure de reception, bouton arme, bouton en cours, message bref. */
internal object HudStore {
  private const val PREFS = "aura_hud"

  private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun save(c: Context, feed: JSONObject) {
    p(c).edit().putString("feed", feed.toString()).putLong("at", System.currentTimeMillis()).apply()
    HudSurfaces.onFeed(c)  // 1.9.0 : notification, Live Update, tuile, montre (le widget est rendu par l'appelant)
  }

  fun feed(c: Context): JSONObject? = p(c).getString("feed", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }

  fun at(c: Context): Long = p(c).getLong("at", 0L)

  fun arm(c: Context, key: String?, until: Long) {
    p(c).edit().putString("armed", key).putLong("armed_until", until).apply()
  }

  fun armed(c: Context): Pair<String, Long>? = p(c).getString("armed", null)?.let { it to p(c).getLong("armed_until", 0L) }

  fun pending(c: Context, key: String?) {
    p(c).edit().putString("pending", key).putLong("pending_at", System.currentTimeMillis()).apply()
  }

  /** Bouton en cours d'envoi ; oublie au-dela de HudLogic.PENDING_TTL_MS (processus tue pendant l'envoi). */
  fun pending(c: Context, nowMs: Long = System.currentTimeMillis()): String? {
    val k = p(c).getString("pending", null) ?: return null
    return if (nowMs - p(c).getLong("pending_at", 0L) <= HudLogic.PENDING_TTL_MS) k else null
  }

  fun toast(c: Context, msg: String?) {
    p(c).edit().putString("toast", msg).putLong("toast_at", System.currentTimeMillis()).apply()
  }

  fun toast(c: Context, nowMs: Long, ttlMs: Long): String? {
    val m = p(c).getString("toast", null) ?: return null
    return if (nowMs - p(c).getLong("toast_at", 0L) <= ttlMs) m else null
  }
}

/**
 * Relais d'un appui dans la liste du grand format (une liste n'a qu'un gabarit de PendingIntent) : ouvre l'app au bon
 * endroit, ou renvoie l'action au widget (broadcast explicite, traite par AuraHudWidget.onTap), puis se ferme sans rien
 * afficher. Une activite et non un broadcast : un recepteur ne peut pas toujours ouvrir l'app depuis l'arriere-plan.
 */
class HudTapActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val open = intent.getStringExtra(AuraHudWidget.EXTRA_OPEN)
    val action = intent.getStringExtra(AuraHudWidget.EXTRA_ACTION)
    if (!open.isNullOrEmpty() && open.startsWith("aura://")) {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(open)).setClassName(this, "dev.aura.mobile.MainActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    } else if (!action.isNullOrEmpty()) {
      sendBroadcast(Intent(this, AuraHudWidget::class.java).setAction(AuraHudWidget.ACT_TAP)
        .putExtra(AuraHudWidget.EXTRA_ACTION, action).putExtra(AuraHudWidget.EXTRA_ID, intent.getStringExtra(AuraHudWidget.EXTRA_ID) ?: ""))
    }
    finish()
    @Suppress("DEPRECATION") overridePendingTransition(0, 0)
  }
}
