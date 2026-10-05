package dev.aura.mobile.device

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Live Update Android 16 (PROTOCOL.md §7.4, purement local) : pendant qu'Aura repond a une demande
 * partie du telephone (montre, Talk, assistant, partage, PWA de l'onglet Aura), notification promue
 * `Notification.ProgressStyle` : puce dans la barre d'etat, ecran verrouille, Now Bar de One UI 8.
 * Repli : notification ongoing classique (Android < 16, ou promotion refusee par l'utilisateur).
 *
 * Les demandes SUIVIES (`track`) sont montrees des le premier mot, avec « Arreter ». Les tours des autres clients
 * (autre client du bridge, autre onglet) que le bridge diffuse a tous sont « etrangers » : montres seulement quand la tache est
 * LONGUE (90 s, LiveLogic), sans bouton Arreter. Aucun ordre ne part du telephone pour eux.
 */
object LiveUpdate {
  private const val MIN_INTERVAL_MS = 700L
  private const val STALE_MS = 10 * 60_000L
  private const val TRACK_MAX_AGE_MS = 30 * 60_000L
  // cle de Notification.EXTRA_REQUEST_PROMOTED_ONGOING (API 36.1) ; lue par le systeme des Android 16
  private const val EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing"

  /** Ecrans ou la reponse est deja sous les yeux de l'utilisateur : pas de Live Update. */
  private val SELF_SCREENS = setOf("Aura", "Talk")

  private class Turn(val conv: String, var id: String) {
    var started = System.currentTimeMillis()
    var lastEvent = started
    var state = "thinking"
    var tool: String? = null
    var toolActive = false
    val text = StringBuilder()
    /** Tour parti d'un autre client : visible seulement une fois long (LiveLogic.LONG_MS). */
    var foreign = false
    /** Le bridge envoie `run_state` pour ce tour : duree et etape viennent de lui, plus d'une minuterie locale. */
    var serverDriven = false
  }

  private val tracked = LinkedHashMap<String, Long>()  // id de demande -> ts
  private val turns = LinkedHashMap<String, Turn>()  // conv -> tour en cours
  @Volatile var screen: String = ""
    private set
  /** Surcouche de l'assistant visible (elle montre deja la reponse). */
  @Volatile var overlay = false
  private var shown = false
  private var lastPost = 0L
  private var scheduled: ScheduledFuture<*>? = null
  private var dueTask: ScheduledFuture<*>? = null

  /** Une demande partie du telephone : ses evenements alimenteront la notification. */
  fun track(id: String) {
    synchronized(this) {
      val limit = System.currentTimeMillis() - TRACK_MAX_AGE_MS
      tracked.entries.removeAll { it.value < limit }
      tracked[id] = System.currentTimeMillis()
      turns.values.filter { it.id == id }.forEach { it.foreign = false }  // l'echo est arrive avant l'avis de la PWA
    }
  }

  fun setScreen(name: String) {
    screen = name
    render(force = true)
  }

  fun onForegroundChanged() = render(force = true)

  /** Tous les messages du bridge passent ici (thread Aura.io). */
  fun onBridge(msg: JSONObject) {
    val changed = synchronized(this) { apply(msg) }
    if (changed) render(force = false)
  }

  fun clearAll() {
    synchronized(this) { turns.clear() }
    render(force = true)
  }

  private fun apply(msg: JSONObject): Boolean {
    val conv = msg.str("conv") ?: return false
    val now = System.currentTimeMillis()
    when (msg.optString("type")) {
      "message" -> {
        val m = msg.optJSONObject("message") ?: return false
        val id = m.str("id") ?: return false
        if (m.str("role") != "me") return false
        turns[conv] = turns[conv]?.also { it.id = id; it.foreign = id !in tracked } ?: Turn(conv, id).also { it.foreign = id !in tracked }
        return true
      }
      "delta" -> {
        val id = msg.str("id") ?: return false
        var t = turns[conv]
        if (t == null) {
          t = Turn(conv, id).also { it.foreign = id !in tracked }
          turns[conv] = t
        }
        t.lastEvent = now
        if (t.id.isEmpty() || t.foreign) { t.id = id; t.foreign = id !in tracked }  // `state` est arrive avant l'echo : on apprend l'id ici
        val tool = msg.str("tool")
        val text = msg.str("text").orEmpty()
        if (tool != null) {
          t.tool = tool
          t.toolActive = true
        }
        if (text.isNotEmpty()) {
          t.state = "talking"
          t.toolActive = false
          t.text.append(text)
          if (t.text.length > 600) t.text.delete(0, t.text.length - 400)
        }
        return true
      }
      "state" -> {
        val value = msg.optString("value")
        // pas d'`id` dans `state` : un tour que nous n'avons pas vu commencer est etranger (autre client du bridge...)
        val t = turns[conv] ?: if (value == "thinking" || value == "talking") Turn(conv, "").also { it.foreign = true; turns[conv] = it } else return false
        when (value) {
          "idle" -> turns.remove(conv)
          "talking" -> { t.state = "talking"; t.lastEvent = now }
          "thinking" -> t.lastEvent = now
        }
        return true
      }
      "run_state" -> {
        // le bridge dit ou en est le tour (toutes les 5 s apres 30 s) : duree reelle et etape en cours (PROTOCOL.md §11.D)
        val rs = LiveLogic.parseRunState(msg) ?: return false
        val t = turns[conv] ?: Turn(conv, "").also { it.foreign = true; turns[conv] = it }  // tour que nous n'avons pas vu commencer
        t.serverDriven = true
        t.started = LiveLogic.startedFrom(t.started, now, rs.elapsedS)
        t.lastEvent = now
        when (LiveLogic.stepKind(rs.step)) {
          LiveLogic.StepKind.TOOL -> { t.tool = rs.step; t.toolActive = true }
          LiveLogic.StepKind.WRITING -> { t.state = "talking"; t.toolActive = false }
          LiveLogic.StepKind.THINKING -> { t.state = "thinking"; t.toolActive = false }
        }
        return true
      }
      "done" -> {
        if (turns.remove(conv) == null) return false
        msg.str("id")?.let { tracked.remove(it) }
        return true
      }
    }
    return false
  }

  private fun suppressed(): Boolean = overlay || Aura.appForeground && screen in SELF_SCREENS

  /** Tour le plus recent encore vivant ET visible (un tour etranger attend d'etre long), null s'il n'y a rien a montrer. */
  private fun current(): Turn? = synchronized(this) {
    val now = System.currentTimeMillis()
    turns.entries.removeAll { it.value.lastEvent < now - STALE_MS }
    turns.values.filter { LiveLogic.visible(it.foreign, now - it.started) }.maxByOrNull { it.lastEvent }
  }

  /** Delai avant que le prochain tour etranger devienne long, null s'il n'y en a pas : sans nouveau message, rien ne rappellerait render. */
  private fun nextDue(): Long? = synchronized(this) {
    val now = System.currentTimeMillis()
    // un tour suivi par run_state n'a pas besoin de minuterie : chaque run_state (5 s) rappelle render
    turns.values.filter { it.foreign && !it.serverDriven }.map { LiveLogic.dueIn(true, it.started, now) }.filter { it > 0 }.minOrNull()
  }

  private fun render(force: Boolean) {
    val delay: Long
    synchronized(this) {
      val now = System.currentTimeMillis()
      delay = if (force) 0L else (lastPost + MIN_INTERVAL_MS - now).coerceAtLeast(0L)
      if (delay > 0) {
        if (scheduled == null || scheduled!!.isDone) {
          scheduled = Aura.timer.schedule(Runnable { render(force = true) }, delay, TimeUnit.MILLISECONDS)
        }
        return
      }
      lastPost = now
    }
    val t = current()
    if (t == null || suppressed()) {
      if (shown) {
        Notifs.cancel(Aura.app, Notifs.ID_LIVE)
        shown = false
      }
      // un tour etranger devient visible a 90 s meme s'il ne dit plus rien (outil long)
      nextDue()?.let { due ->
        synchronized(this) {
          dueTask?.cancel(false)
          dueTask = Aura.timer.schedule(Runnable { render(force = true) }, due + 50, TimeUnit.MILLISECONDS)
        }
      }
      return
    }
    try {
      post(t)
      shown = true
    } catch (e: Exception) {
      Log.w(Aura.TAG, "Live Update non affiche", e)
    }
  }

  /** « Bash · date » -> « Bash » ; « transcription » -> « Transcription ». */
  fun toolName(label: String): String = LiveLogic.toolName(label)

  fun step(state: String, tool: String?, toolActive: Boolean): String = when {
    tool == "transcription" && toolActive -> "transcription"
    toolActive && tool != null -> toolName(tool)
    state == "talking" -> "répond"
    else -> "réfléchit"
  }

  private fun lastLine(sb: StringBuilder): String {
    val s = sb.toString().trim().replace(Regex("\\s+"), " ")
    return if (s.length <= 120) s else "…" + s.takeLast(119)
  }

  private fun post(t: Turn) {
    val ctx = Aura.app
    val step = step(t.state, t.tool, t.toolActive)
    val title = LiveLogic.title(t.foreign, step)
    val text = when {
      t.toolActive && t.tool != null && t.tool != "transcription" -> t.tool!!
      t.tool == "transcription" && t.toolActive -> "Transcription du vocal…"
      t.text.isNotEmpty() -> lastLine(t.text)
      else -> LiveLogic.waitingText(t.foreign, System.currentTimeMillis() - t.started)
    }
    // « Arreter » seulement pour une demande partie d'ici ; un tour du PC se coupe depuis le PC
    val stop = if (LiveLogic.canStop(t.foreign, t.id)) {
      Notifs.receiverIntent(ctx, Notifs.ID_LIVE, ActionReceiver.ACTION_LIVE_CANCEL, mapOf("id" to t.id))
    } else null
    val private = LiveLogic.lockScreenPrivate(t.foreign)
    val nm = ctx.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 36) {
      // Notification.Builder natif : NotificationCompat 1.13 ne connait ni ProgressStyle ni la promotion
      val b = Notification.Builder(ctx, Notifs.CH_LIVE)
        .setSmallIcon(R.drawable.ic_aura)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(true)
        .setWhen(t.started)
        .setUsesChronometer(true)
        .setCategory(Notification.CATEGORY_PROGRESS)
        .setVisibility(if (private) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
        .setContentIntent(Notifs.launchAppIntent(ctx))
        .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
      if (private) {
        // tour du PC : ecran verrouille = titre neutre seulement (ni texte streame ni outil)
        b.setPublicVersion(
          Notification.Builder(ctx, Notifs.CH_LIVE).setSmallIcon(R.drawable.ic_aura).setContentTitle(LiveLogic.PUBLIC_TITLE)
            .setWhen(t.started).setShowWhen(true).setUsesChronometer(true).build()
        )
      }
      // puce de la barre d'etat : l'outil en cours ; sans outil, le chronometre (setWhen) s'y affiche
      LiveLogic.chip(t.foreign, t.tool, t.toolActive)?.let { b.setShortCriticalText(it) }
      if (stop != null) b.addAction(Notification.Action.Builder(null, "Arrêter", stop).build())
      b.extras.putBoolean(EXTRA_REQUEST_PROMOTED, true)
      nm.notify(Notifs.ID_LIVE, b.build())
      return
    }
    val b = NotificationCompat.Builder(ctx, Notifs.CH_LIVE)
      .setSmallIcon(R.drawable.ic_aura)
      .setContentTitle(title)
      .setContentText(text)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setWhen(t.started)
      .setUsesChronometer(true)
      .setProgress(0, 0, true)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setVisibility(if (private) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
      .setContentIntent(Notifs.launchAppIntent(ctx))
    if (private) {
      b.setPublicVersion(
        NotificationCompat.Builder(ctx, Notifs.CH_LIVE).setSmallIcon(R.drawable.ic_aura).setContentTitle(LiveLogic.PUBLIC_TITLE).build()
      )
    }
    if (stop != null) b.addAction(0, "Arrêter", stop)
    Notifs.post(ctx, Notifs.ID_LIVE, b)
  }

  /** Android 16 : l'utilisateur peut couper la promotion par appli (Paramètres → Notifications → Live Updates). */
  fun canPromote(): Boolean {
    if (Build.VERSION.SDK_INT < 36) return false
    return try {
      Aura.app.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
    } catch (e: Exception) {
      false
    }
  }
}
