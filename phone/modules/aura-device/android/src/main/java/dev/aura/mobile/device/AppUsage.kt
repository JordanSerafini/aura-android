package dev.aura.mobile.device

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/*
 * `app_usage {period?, limit?}` (03/10, PROTOCOL.md §19.5) : temps d'ecran par app, par UsageStatsManager.
 * Permission speciale « Acces a l'utilisation » (PACKAGE_USAGE_STATS) : l'utilisateur l'accorde a la main (onglet Actions ->
 * « Accès à l'utilisation ») ; sans elle, l'action repond `permission:usage_access` avec le chemin exact.
 *
 * Mesure :
 *  - `24h` (defaut) et `today` : a partir des EVENEMENTS (premier plan / arriere-plan de chaque activite), donc exact a la
 *    minute pres sur la fenetre demandee ;
 *  - `7d` : a partir des totaux JOURNALIERS d'Android (queryAndAggregateUsageStats), qui comptent les journees entieres
 *    touchees par la fenetre : `precision: "jour"`.
 * Ecran d'accueil (lanceur) et interface systeme exclus : ce n'est pas « une app utilisee ».
 */
object AppUsageLogic {
  const val RESUMED = 1          // UsageEvents.Event.ACTIVITY_RESUMED (ex MOVE_TO_FOREGROUND)
  const val PAUSED = 2           // ACTIVITY_PAUSED (ex MOVE_TO_BACKGROUND)
  const val STOPPED = 23         // ACTIVITY_STOPPED
  const val SCREEN_OFF = 16      // SCREEN_NON_INTERACTIVE : tout ce qui etait au premier plan s'arrete la
  const val SHUTDOWN = 26        // DEVICE_SHUTDOWN
  val PERIODS = setOf("24h", "today", "7d")

  class Ev(val pkg: String, val cls: String, val type: Int, val ts: Long)

  /**
   * Temps au premier plan par paquet (ms) sur [begin, end], d'apres les evenements tries par date. Une activite vue d'abord
   * en PAUSED etait deja au premier plan au debut de la fenetre : comptee depuis `begin`. Encore ouverte a la fin : jusqu'a
   * `end`. Plusieurs activites d'une meme app ouvertes en meme temps ne comptent qu'une fois.
   */
  fun foreground(events: List<Ev>, begin: Long, end: Long): Map<String, Long> {
    val open = HashMap<String, HashMap<String, Long>>()  // pkg -> (classe -> debut)
    val since = HashMap<String, Long>()                 // pkg -> debut de la session de l'app
    val seen = HashSet<String>()
    val total = HashMap<String, Long>()
    fun close(pkg: String, at: Long) {
      val start = since.remove(pkg) ?: return
      val d = minOf(at, end) - maxOf(start, begin)
      if (d > 0) total[pkg] = (total[pkg] ?: 0L) + d
    }
    for (e in events.sortedBy { it.ts }) {
      if (e.ts > end) break
      when (e.type) {
        RESUMED -> {
          seen += "${e.pkg}/${e.cls}"
          val acts = open.getOrPut(e.pkg) { HashMap() }
          if (acts.isEmpty()) since[e.pkg] = maxOf(e.ts, begin)
          acts[e.cls] = e.ts
        }
        PAUSED, STOPPED -> {
          val key = "${e.pkg}/${e.cls}"
          val acts = open.getOrPut(e.pkg) { HashMap() }
          if (key !in seen && e.type == PAUSED) {
            // au premier plan avant la fenetre : de begin a maintenant
            seen += key
            if (acts.isEmpty() && since[e.pkg] == null) since[e.pkg] = begin
            acts[e.cls] = begin
          }
          if (acts.remove(e.cls) != null && acts.isEmpty()) close(e.pkg, e.ts)
        }
        SCREEN_OFF, SHUTDOWN -> {
          for (pkg in open.keys.toList()) {
            if (open[pkg]?.isNotEmpty() == true) close(pkg, e.ts)
            open[pkg]?.clear()
          }
        }
      }
    }
    for ((pkg, acts) in open) if (acts.isNotEmpty()) close(pkg, end)
    return total
  }

  /** Classement : minutes arrondies, < 1 min ecarte, exclusions retirees, les plus longues d'abord. */
  fun ranked(ms: Map<String, Long>, excluded: Set<String>, limit: Int): List<Pair<String, Long>> =
    ms.filter { (pkg, v) -> pkg !in excluded && v >= 60_000L }
      .entries.sortedByDescending { it.value }
      .take(limit.coerceIn(1, 50))
      .map { it.key to it.value }

  /** Debut de la fenetre (ms). */
  fun begin(period: String, now: Long, zone: ZoneId): Long = when (period) {
    "today" -> Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    "7d" -> now - 7 * 86_400_000L
    else -> now - 86_400_000L
  }
}

object AppUsage {
  private val ctx get() = Aura.app

  /** « Acces a l'utilisation » accorde ? (app-op, pas une permission d'execution) */
  fun granted(): Boolean = try {
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    val mode = if (Build.VERSION.SDK_INT >= 29) {
      ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    } else {
      @Suppress("DEPRECATION")
      ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    }
    mode == AppOpsManager.MODE_ALLOWED
  } catch (e: Exception) {
    false
  }

  private fun excluded(): Set<String> {
    val out = mutableSetOf("com.android.systemui")
    try {
      val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
      @Suppress("DEPRECATION")
      ctx.packageManager.queryIntentActivities(home, 0).forEach { out += it.activityInfo.packageName }
    } catch (e: Exception) { /* liste de lanceurs illisible : seule l'interface systeme est exclue */ }
    return out
  }

  private fun label(pkg: String): String = try {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
  } catch (e: Exception) {
    pkg
  }

  private fun iso(ms: Long): String = OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).toString()

  fun query(periodRaw: String?, limit: Int): JSONObject {
    val period = (periodRaw ?: "24h").trim().lowercase()
    if (period !in AppUsageLogic.PERIODS) throw ActionError("bad_params", "period : 24h, today ou 7d")
    if (!granted()) {
      throw ActionError("permission:usage_access", "Accès à l'utilisation non accordé sur le téléphone : app Aura, onglet " +
        "Actions → « Accès à l'utilisation » (ou Paramètres → Applications → Accès spécial → Accès aux données d'utilisation → Aura)")
    }
    val usm = ctx.getSystemService(UsageStatsManager::class.java)
      ?: throw ActionError("unsupported", "Statistiques d'utilisation indisponibles sur ce téléphone")
    val now = System.currentTimeMillis()
    val begin = AppUsageLogic.begin(period, now, ZoneId.systemDefault())
    val ms: Map<String, Long>
    val last = HashMap<String, Long>()
    val precision: String
    if (period == "7d") {
      val agg = usm.queryAndAggregateUsageStats(begin, now)
      ms = agg.mapValues { (_, st) -> if (Build.VERSION.SDK_INT >= 29) st.totalTimeVisible else st.totalTimeInForeground }
      agg.forEach { (pkg, st) -> if (st.lastTimeUsed > 0) last[pkg] = st.lastTimeUsed }
      precision = "jour"
    } else {
      val list = ArrayList<AppUsageLogic.Ev>()
      val events = usm.queryEvents(begin, now)
      val e = UsageEvents.Event()
      while (events.hasNextEvent()) {
        events.getNextEvent(e)
        val t = e.eventType
        if (t == AppUsageLogic.RESUMED || t == AppUsageLogic.PAUSED || t == AppUsageLogic.STOPPED ||
          t == AppUsageLogic.SCREEN_OFF || t == AppUsageLogic.SHUTDOWN) {
          list += AppUsageLogic.Ev(e.packageName ?: "", e.className ?: "", t, e.timeStamp)
          if (t == AppUsageLogic.RESUMED || t == AppUsageLogic.PAUSED) last[e.packageName ?: ""] = e.timeStamp
        }
      }
      ms = AppUsageLogic.foreground(list, begin, now)
      precision = "minute"
    }
    val excl = excluded()
    val top = AppUsageLogic.ranked(ms, excl, limit)
    val totalMs = ms.filter { (pkg, v) -> pkg !in excl && v >= 60_000L }.values.sum()
    val items = JSONArray()
    for ((pkg, v) in top) {
      items.put(JSONObject().put("app", pkg).put("app_name", label(pkg)).put("minutes", Math.round(v / 60_000.0))
        .apply { last[pkg]?.let { put("last_used", iso(it)) } })
    }
    return JSONObject().put("period", period).put("since", iso(begin)).put("until", iso(now)).put("precision", precision)
      .put("total_min", Math.round(totalMs / 60_000.0)).put("items", items).put("count", items.length())
  }
}
