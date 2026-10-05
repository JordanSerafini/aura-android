package dev.aura.mobile.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Execute les action_request du bridge (docs/PROTOCOL.md §1-2) et renvoie action_result. */
class ActionExecutor {
  private class Running(val id: String) {
    @Volatile var cancelled = false
  }

  private val running = ConcurrentHashMap<String, Running>()
  /** La demande en cours sur ce thread a-t-elle ete confirmee par l'utilisateur ? (envoi WhatsApp par accessibilite) */
  private val confirmed = ThreadLocal.withInitial { false }
  private val ctx: Context get() = Aura.app

  companion object {
    val APPS = mapOf(
      "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
      "messenger" to listOf("com.facebook.orca", "com.facebook.mlite"),
      "telegram" to listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"),
      "signal" to listOf("org.thoughtcrime.securesms"),
      "sms" to listOf("com.samsung.android.messaging", "com.google.android.apps.messaging"),
    )
  }

  // ─── Cycle d'une demande ────────────────────────────────────────────────

  fun onRequest(msg: JSONObject) {
    val id = msg.str("action_id") ?: return
    val action = msg.optString("action")
    val params = msg.optJSONObject("params") ?: JSONObject()
    val confirm = msg.optBoolean("confirm", false)
    val summary = msg.str("summary") ?: action
    val timeoutS = msg.optInt("timeout_s", 60)
    // l'app ne peut que DURCIR la decision du bridge : ui_act tap / type se confirme toujours (PROTOCOL.md §9.2)
    val mustConfirm = confirm || UiGuard.needsConfirm(action, params)
    val r = Running(id)
    running[id] = r
    Aura.pool.execute {
      val t0 = System.currentTimeMillis()
      val res = JSONObject().put("type", "action_result").put("action_id", id)
      try {
        // Pause d'Aura : refus local (meme hors ligne, meme si le bridge n'a pas encore vu la pause), AVANT la
        // confirmation : on ne derange pas l'utilisateur pour une action qui sera refusee
        if (action == "ui_act") Journal.noteGesture(params.str("app") ?: "?", params.str("op") ?: "?")  // l'app visee, si le service n'a pas pu mieux dire
        Pause.check(action)
        // ui_act : liste noire, liste blanche, forme AVANT de deranger l'utilisateur avec une confirmation qui serait refusee
        if (action == "ui_act") UiGuard.plan(params, UiSettings.whitelist())
        if (mustConfirm) {
          when (Aura.confirms.ask(id, action, summary, timeoutS - 3)) {
            // la pause a pu etre posee PENDANT l'attente (montre, PC, notification) : on re-verifie avant d'agir
            "accepted" -> Pause.check(action)
            "refused" -> throw ActionError("refused", "L'utilisateur a refusé")
            "cancelled" -> r.cancelled = true
            else -> throw ActionError("timeout", "Pas de réponse à la demande de confirmation")
          }
        }
        if (!r.cancelled) {
          confirmed.set(mustConfirm)
          val out = try { run(action, params) } finally { confirmed.set(false) }
          res.put("ok", true)
          if (out != null) res.put("result", out)
        }
      } catch (e: ActionError) {
        fail(res, e.code, e.message, e.extra)
      } catch (e: SecurityException) {
        fail(res, "permission:${permFor(action)}", "Android a refusé : ${e.message}", null)
      } catch (e: Exception) {
        Log.w(Aura.TAG, "action $action en echec", e)
        fail(res, "failed", "${e.javaClass.simpleName} : ${e.message}", null)
      }
      running.remove(id)
      val ms = System.currentTimeMillis() - t0
      if (r.cancelled) {
        log(action, summary, "bridge", mustConfirm, false, "annulée par le bridge", ms, params)
      } else {
        Aura.bridge.send(res)
        log(action, summary, "bridge", mustConfirm, res.optBoolean("ok"), res.str("error"), ms, params)
      }
    }
  }

  fun onCancel(id: String) {
    running[id]?.cancelled = true
    Aura.confirms.cancel(id)
  }

  /** Test local depuis l'app (sans confirmation, sans bridge). */
  fun runLocal(action: String, params: JSONObject): JSONObject {
    val t0 = System.currentTimeMillis()
    val res = JSONObject()
    try {
      // pas de fenetre de confirmation ici : toucher / saisir dans une app ne se teste pas en local
      if (UiGuard.needsConfirm(action, params)) throw ActionError("refused", "ui_act tap / type : seulement sur demande d'Aura, avec ta confirmation")
      val out = run(action, params)
      res.put("ok", true).put("result", out ?: JSONObject.NULL)
    } catch (e: ActionError) {
      fail(res, e.code, e.message, e.extra)
    } catch (e: Exception) {
      fail(res, "failed", "${e.javaClass.simpleName} : ${e.message}", null)
    }
    log(action, "test depuis l'app", "app", false, res.optBoolean("ok"), res.str("error"), System.currentTimeMillis() - t0)
    return res
  }

  /** Nom de permission du protocole (PROTOCOL.md §5) pour une SecurityException levee par Android. */
  private fun permFor(action: String): String = when (action) {
    "sms_list" -> "sms_read"
    "call" -> "phone"
    "contacts_search" -> "contacts"
    "calendar_list" -> "calendar_read"
    "calendar_add" -> "calendar_write"
    "camera_snap" -> "camera"
    "screen_read", "ui_act" -> "accessibility"
    "location_get" -> "location"
    "dnd_set", "volume_set", "ringer_mode" -> "dnd_access"
    "notif_list", "notif_reply", "notif_dismiss", "notif_open", "media_now" -> "notification_listener"
    "app_usage" -> "usage_access"
    else -> action  // sms_send, call_log : meme nom
  }

  /** error = code normalise (refused, timeout, unsupported, permission:<nom>…), message = explication. */
  private fun fail(res: JSONObject, code: String, message: String?, extra: JSONObject?) {
    res.put("ok", false).put("error", code).put("message", message ?: code)
    if (extra != null) res.put("result", extra)
  }

  private fun log(action: String, summary: String, source: String, confirm: Boolean, ok: Boolean, error: String?, ms: Long, params: JSONObject? = null) {
    // ui_act : le service dit sur quelle app et quel geste il a vraiment agi (scroll / back / home compris)
    val gesture = Journal.takeGesture()
    Aura.addLog(JournalLogic.actionEntry(action, summary, source, confirm, ok, error, ms, gesture?.first, gesture?.second, params))
  }

  // ─── Aiguillage ─────────────────────────────────────────────────────────

  fun run(action: String, p: JSONObject): Any? = when (action) {
    "sms_send" -> smsSend(req(p, "to"), req(p, "text"))
    "sms_list" -> smsList(p.optInt("limit", 20), p.str("from"), p.optBoolean("unread_only", false))
    "call" -> call(req(p, "to"))
    "call_log" -> callLog(p.optInt("limit", 20))
    "contacts_search" -> contactsSearch(req(p, "q"), p.optInt("limit", 10))
    "notif_list" -> notifList(p.str("app"), p.optInt("limit", 30))
    "notif_reply" -> { AuraNotificationListener.reply(AuraNotificationListener.find(req(p, "key")), req(p, "text")); JSONObject().put("mode", "replied") }
    "notif_dismiss" -> { AuraNotificationListener.dismiss(req(p, "key")); null }
    "notif_open" -> { AuraNotificationListener.open(req(p, "key")); null }
    "message_send" -> messageSend(req(p, "app").lowercase(), req(p, "to"), req(p, "text"))
    "calendar_list" -> calendarList(p.optInt("days", 7))
    "calendar_add" -> calendarAdd(p)
    "alarm_set" -> alarmSet(p)
    "timer_set" -> timerSet(p)
    "location_get" -> locationGet()
    "device_status" -> deviceStatus()
    "app_usage" -> AppUsage.query(p.str("period"), p.optInt("limit", 15))
    "volume_set" -> volumeSet(req(p, "stream"), p.optInt("percent", -1))
    "dnd_set" -> dndSet(p.optBoolean("on"))
    "ringer_mode" -> ringerMode(req(p, "mode"))
    "flashlight" -> flashlight(p.optBoolean("on"))
    "find_phone" -> { FindPhone.start(30); JSONObject().put("ringing_s", 30) }
    "media_control" -> mediaControl(req(p, "cmd"))
    "media_now" -> mediaNow()
    "open_app" -> openApp(p.str("package"), p.str("name"))
    "open_url" -> JSONObject().put("mode", Launcher.start(Intent(Intent.ACTION_VIEW, Uri.parse(req(p, "url"))), req(p, "url")))
    "navigate" -> navigate(req(p, "destination"), p.str("mode"))
    "clipboard_set" -> { clipboard().setPrimaryClip(ClipData.newPlainText("Aura", req(p, "text"))); null }
    "clipboard_get" -> clipboardGet()
    "email_compose" -> emailCompose(req(p, "to"), p.str("subject").orEmpty(), p.str("body").orEmpty())
    "speak" -> { Speaker.speak(req(p, "text")); null }
    "camera_snap" -> CameraCapture.snap(p.str("facing") ?: "back")
    "screen_read" -> AuraAccessibilityService.readScreen()
    // liste blanche relue A L'EXECUTION (l'utilisateur a pu la changer pendant qu'il confirmait)
    "ui_act" -> AuraAccessibilityService.uiAct(UiGuard.plan(p, UiSettings.whitelist()))
    "watch_notify" -> Aura.watch.cmd("notify", p)
    "watch_vibrate" -> Aura.watch.cmd("vibrate", p)
    "watch_heart_rate" -> Aura.watch.cmd("heart_rate", p)
    "watch_steps" -> Aura.watch.cmd("steps", p)
    "watch_battery" -> Aura.watch.cmd("battery", p)
    else -> throw ActionError("unsupported", "Action inconnue du téléphone : $action")
  }

  private fun req(p: JSONObject, key: String): String {
    val v = p.str(key)?.trim()
    if (v.isNullOrEmpty()) throw ActionError("bad_params", "Paramètre manquant : $key")
    return v
  }

  // ─── SMS, appels, contacts ──────────────────────────────────────────────

  @SuppressLint("MissingPermission")
  private fun smsSend(to: String, text: String): JSONObject {
    if (!Perms.hasTelephony()) throw ActionError("unsupported", "Pas de téléphonie sur cet appareil")
    Perms.requireRuntime("sms_send", Manifest.permission.SEND_SMS)
    val (number, name) = Contacts.resolve(to)
    val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
    else @Suppress("DEPRECATION") SmsManager.getDefault()
    val parts = sms.divideMessage(text)
    val action = "dev.aura.mobile.SMS_SENT." + System.nanoTime()
    val latch = CountDownLatch(parts.size)
    val failures = AtomicInteger(0)
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, i: Intent) {
        if (resultCode != android.app.Activity.RESULT_OK) failures.incrementAndGet()
        latch.countDown()
      }
    }
    ContextCompat.registerReceiver(ctx, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
    try {
      val sent = ArrayList<PendingIntent>()
      parts.indices.forEach { idx ->
        sent += PendingIntent.getBroadcast(ctx, idx, Intent(action).setPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE)
      }
      sms.sendMultipartTextMessage(number, null, parts, sent, null)
      val finished = latch.await(30, TimeUnit.SECONDS)
      if (finished && failures.get() > 0) throw ActionError("send_failed", "Le SMS n'est pas parti (réseau ?)")
      return JSONObject().put("to", number).put("name", name ?: JSONObject.NULL).put("parts", parts.size)
        .put("status", if (finished) "sent" else "pending")
    } finally {
      try { ctx.unregisterReceiver(receiver) } catch (e: Exception) { /* deja retire */ }
    }
  }

  private fun smsList(limit: Int, from: String?, unreadOnly: Boolean): JSONObject {
    Perms.requireRuntime("sms_read", Manifest.permission.READ_SMS)
    var number: String? = null
    var needle: String? = null
    if (!from.isNullOrBlank()) {
      try {
        number = Contacts.resolve(from).first
      } catch (e: ActionError) {
        if (e.code == "ambiguous") throw e
        needle = from.lowercase()  // expediteur alphanumerique (« AMELI ») ou contact absent
      }
    }
    val items = JSONArray()
    val sel = if (unreadOnly) "${Telephony.Sms.READ} = 0" else null
    ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
      arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ),
      sel, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
      var scanned = 0
      while (c.moveToNext() && items.length() < limit.coerceIn(1, 200) && scanned < 2000) {
        scanned++
        val addr = c.getString(0) ?: ""
        if (number != null && !Contacts.same(addr, number)) continue
        if (needle != null && !addr.lowercase().contains(needle)) continue
        items.put(JSONObject()
          .put("from", addr)
          .put("name", Contacts.nameFor(addr) ?: JSONObject.NULL)
          .put("text", (c.getString(1) ?: "").take(1000))
          .put("ts", iso(c.getLong(2)))
          .put("read", c.getInt(3) == 1))
      }
    }
    return JSONObject().put("items", items).put("count", items.length())
  }

  @SuppressLint("MissingPermission")
  private fun call(to: String): JSONObject {
    if (!Perms.hasTelephony()) throw ActionError("unsupported", "Pas de téléphonie sur cet appareil")
    Perms.requireRuntime("phone", Manifest.permission.CALL_PHONE)
    val (number, name) = Contacts.resolve(to)
    // TelecomManager.placeCall : pas d'activite a lancer, donc pas de blocage en arriere-plan
    ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
    return JSONObject().put("to", number).put("name", name ?: JSONObject.NULL)
  }

  private fun callLog(limit: Int): JSONObject {
    Perms.requireRuntime("call_log", Manifest.permission.READ_CALL_LOG)
    val items = JSONArray()
    ctx.contentResolver.query(CallLog.Calls.CONTENT_URI,
      arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
      null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
      while (c.moveToNext() && items.length() < limit.coerceIn(1, 200)) {
        val type = when (c.getInt(2)) {
          CallLog.Calls.INCOMING_TYPE -> "incoming"
          CallLog.Calls.OUTGOING_TYPE -> "outgoing"
          CallLog.Calls.MISSED_TYPE -> "missed"
          CallLog.Calls.REJECTED_TYPE -> "rejected"
          CallLog.Calls.BLOCKED_TYPE -> "blocked"
          CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
          else -> "other"
        }
        items.put(JSONObject()
          .put("number", c.getString(0) ?: "")
          .put("name", c.getString(1) ?: JSONObject.NULL)
          .put("type", type)
          .put("ts", iso(c.getLong(3)))
          .put("duration_s", c.getLong(4)))
      }
    }
    return JSONObject().put("items", items).put("count", items.length())
  }

  private fun contactsSearch(q: String, limit: Int): JSONObject {
    val found = Contacts.search(q, 200).take(limit.coerceIn(1, 100))
    return JSONObject().put("items", JSONArray(found.map { Contacts.toJson(it) })).put("count", found.size)
  }

  // ─── Notifications, messageries ─────────────────────────────────────────

  private fun appMatches(info: AuraNotificationListener.Info, app: String): Boolean {
    val a = app.lowercase()
    val pkgs = APPS[a]
    return info.app == a || (pkgs != null && info.app in pkgs) || info.label.lowercase().contains(a)
  }

  private fun notifList(app: String?, limit: Int): JSONObject {
    val list = AuraNotificationListener.active()
      .filter { app.isNullOrBlank() || appMatches(it, app) }
      .take(limit.coerceIn(1, 100))
    return JSONObject().put("items", JSONArray(list.map { it.toJson() })).put("count", list.size)
  }

  private fun smsPackages(): List<String> =
    listOfNotNull(Telephony.Sms.getDefaultSmsPackage(ctx)) + APPS.getValue("sms")

  private fun messageSend(app: String, to: String, text: String): JSONObject {
    val pkgs = if (app == "sms") smsPackages() else APPS[app]
      ?: throw ActionError("unsupported", "Messagerie inconnue : $app (whatsapp, messenger, telegram, signal, sms)")
    // 1. notification active de cette conversation avec reponse directe
    if (Perms.notifListener() && AuraNotificationListener.instance != null) {
      val targets = mutableSetOf(Contacts.norm(to))
      if (Contacts.looksLikeNumber(to)) Contacts.nameFor(to)?.let { targets += Contacts.norm(it) }
      else if (Perms.granted(Manifest.permission.READ_CONTACTS)) {
        try { Contacts.resolve(to).second?.let { targets += Contacts.norm(it) } } catch (e: ActionError) { /* nom libre */ }
      }
      targets.removeAll { it.length < 2 }
      val candidates = AuraNotificationListener.active().filter { it.app in pkgs && it.replyAction != null }
      fun names(i: AuraNotificationListener.Info) = listOfNotNull(i.conversation, i.title).map { Contacts.norm(it) }
      val exact = candidates.filter { i -> names(i).any { it in targets } }
      val loose = candidates.filter { i -> names(i).any { n -> targets.any { t -> n.contains(t) } } }
      val hit = exact.firstOrNull() ?: loose.singleOrNull()
      if (hit != null) {
        AuraNotificationListener.reply(hit.sbn, text)
        return JSONObject().put("mode", "replied").put("app", app).put("conversation", hit.conversation ?: hit.title)
      }
    }
    // 2. ouverture de la conversation pre-remplie : l'utilisateur appuie sur Envoyer
    val installed = pkgs.firstOrNull { isInstalled(it) }
    val enc = Uri.encode(text)
    val result = JSONObject().put("app", app)
    val intent: Intent = when (app) {
      "whatsapp" -> {
        val num = Contacts.international(Contacts.resolve(to).first)
        Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$num?text=$enc")).apply { installed?.let { setPackage(it) } }
      }
      "sms" -> {
        val num = Contacts.resolve(to).first
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num")).putExtra("sms_body", text)
      }
      "telegram" -> {
        val t = to.trim()
        val uri = when {
          t.startsWith("@") -> "tg://resolve?domain=${Uri.encode(t.drop(1))}&text=$enc"
          else -> try {
            "tg://resolve?phone=${Contacts.international(Contacts.resolve(t).first)}&text=$enc"
          } catch (e: ActionError) {
            if (e.code == "ambiguous") throw e
            "tg://msg?text=$enc"
          }
        }
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply { installed?.let { setPackage(it) } }
      }
      "signal" -> {
        // Signal n'accepte pas de texte dans son lien : il est copie dans le presse-papiers
        val num = Contacts.international(Contacts.resolve(to).first)
        clipboard().setPrimaryClip(ClipData.newPlainText("Aura", text))
        result.put("text_in_clipboard", true)
        Intent(Intent.ACTION_VIEW, Uri.parse("sgnl://signal.me/#p/+$num")).apply { installed?.let { setPackage(it) } }
      }
      "messenger" -> {
        // pas de lien profond par destinataire : partage du texte dans Messenger, l'utilisateur choisit la conversation
        result.put("pick_conversation", true)
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).apply { installed?.let { setPackage(it) } }
      }
      else -> throw ActionError("unsupported", "Messagerie inconnue : $app")
    }
    if (app != "sms" && installed == null) throw ActionError("unsupported", "$app n'est pas installé")
    val mode = if (Launcher.start(intent, "$app : $to") == "opened") "opened" else "notification"
    // 3. WhatsApp ouvert, accessibilite active, demande CONFIRMEE par l'utilisateur : appui sur Envoyer a sa place
    if (mode == "opened" && app == "whatsapp" && confirmed.get() == true && AuraAccessibilityService.instance != null) {
      if (AuraAccessibilityService.clickWhatsAppSend()) return result.put("mode", "sent")
      result.put("send_click_failed", true)
    }
    result.put("mode", mode)
    return result
  }

  private fun isInstalled(pkg: String): Boolean = try {
    ctx.packageManager.getPackageInfo(pkg, 0)
    true
  } catch (e: Exception) {
    false
  }

  // ─── Agenda ─────────────────────────────────────────────────────────────

  private fun calendarList(days: Int): JSONObject {
    Perms.requireRuntime("calendar_read", Manifest.permission.READ_CALENDAR)
    val start = System.currentTimeMillis()
    val end = start + days.coerceIn(1, 60) * 86_400_000L
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
    android.content.ContentUris.appendId(uri, start)
    android.content.ContentUris.appendId(uri, end)
    val items = JSONArray()
    ctx.contentResolver.query(uri.build(), arrayOf(
      CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
      CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
    ), "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
      while (c.moveToNext() && items.length() < 200) {
        val allDay = c.getInt(3) == 1
        items.put(JSONObject()
          .put("title", c.getString(0) ?: "")
          .put("start", if (allDay) utcDate(c.getLong(1)) else iso(c.getLong(1)))
          .put("end", if (allDay) utcDate(c.getLong(2)) else iso(c.getLong(2)))
          .put("all_day", allDay)
          .put("location", c.getString(4) ?: JSONObject.NULL)
          .put("calendar", c.getString(5) ?: JSONObject.NULL))
      }
    }
    return JSONObject().put("items", items).put("count", items.length())
  }

  private fun calendarAdd(p: JSONObject): JSONObject {
    Perms.requireRuntime("calendar_write", Manifest.permission.WRITE_CALENDAR)
    val title = req(p, "title")
    val start = parseTime(req(p, "start"))
    val end = p.str("end")?.let { parseTime(it) }
    val (calId, calName) = writableCalendar()
    val v = ContentValues().apply {
      put(CalendarContract.Events.CALENDAR_ID, calId)
      put(CalendarContract.Events.TITLE, title)
      p.str("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
      p.str("notes")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
      if (start.second) {
        put(CalendarContract.Events.ALL_DAY, 1)
        put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        put(CalendarContract.Events.DTSTART, start.first)
        put(CalendarContract.Events.DTEND, end?.first?.takeIf { it > start.first } ?: (start.first + 86_400_000L))
      } else {
        put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
        put(CalendarContract.Events.DTSTART, start.first)
        put(CalendarContract.Events.DTEND, end?.first?.takeIf { it > start.first } ?: (start.first + 3_600_000L))
      }
    }
    val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
      ?: throw ActionError("failed", "L'agenda a refusé l'événement")
    return JSONObject().put("event_id", uri.lastPathSegment).put("calendar", calName)
  }

  private fun writableCalendar(): Pair<Long, String> {
    var best: Triple<Long, String, Int>? = null
    ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(
      CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_TYPE,
      CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.VISIBLE,
    ), null, null, null)?.use { c ->
      while (c.moveToNext()) {
        if (c.getInt(4) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR || c.getInt(5) != 1) continue
        val score = (if (c.getInt(3) == 1) 4 else 0) + (if (c.getString(2) == "com.google") 2 else 0)
        if (best == null || score > best!!.third) best = Triple(c.getLong(0), c.getString(1) ?: "", score)
      }
    }
    val b = best ?: throw ActionError("unsupported", "Aucun agenda modifiable sur le téléphone")
    return b.first to b.second
  }

  /** ISO 8601 -> (epoch ms, journee entiere). Sans fuseau = heure locale du telephone. */
  private fun parseTime(raw: String): Pair<Long, Boolean> {
    val s = raw.trim().replace(' ', 'T')
    try { return OffsetDateTime.parse(s).toInstant().toEpochMilli() to false } catch (e: Exception) { }
    try { return ZonedDateTime.parse(s).toInstant().toEpochMilli() to false } catch (e: Exception) { }
    try { return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() to false } catch (e: Exception) { }
    try { return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to true } catch (e: Exception) { }
    throw ActionError("bad_params", "Date illisible (ISO 8601 attendu) : $raw")
  }

  private fun iso(ms: Long): String = OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).toString()
  private fun utcDate(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()

  // ─── Reveil, minuteur ───────────────────────────────────────────────────

  private fun alarmSet(p: JSONObject): JSONObject {
    val hour = p.optInt("hour", -1)
    val minute = p.optInt("minute", 0)
    if (hour !in 0..23 || minute !in 0..59) throw ActionError("bad_params", "hour 0-23 et minute 0-59 attendus")
    val i = Intent(AlarmClock.ACTION_SET_ALARM)
      .putExtra(AlarmClock.EXTRA_HOUR, hour)
      .putExtra(AlarmClock.EXTRA_MINUTES, minute)
      .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    p.str("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
    p.optJSONArray("days")?.let { arr ->
      // ISO : 1 = lundi … 7 = dimanche ; AlarmClock attend java.util.Calendar (1 = dimanche … 7 = samedi)
      val days = ArrayList<Int>()
      for (k in 0 until arr.length()) {
        val d = arr.optInt(k)
        if (d in 1..7) days += (d % 7) + 1
      }
      if (days.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_DAYS, days)
    }
    return JSONObject().put("mode", Launcher.start(i, "Réveil %02d:%02d".format(hour, minute)))
  }

  private fun timerSet(p: JSONObject): JSONObject {
    val seconds = p.optInt("seconds", 0)
    if (seconds !in 1..86_400) throw ActionError("bad_params", "seconds entre 1 et 86400 attendu")
    val i = Intent(AlarmClock.ACTION_SET_TIMER)
      .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
      .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    p.str("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
    return JSONObject().put("mode", Launcher.start(i, "Minuteur ${seconds}s"))
  }

  // ─── Localisation ───────────────────────────────────────────────────────

  @SuppressLint("MissingPermission")
  private fun locationGet(): JSONObject {
    Perms.require("location", Perms.location())
    if (!Aura.appForeground && !Perms.locationBackground()) {
      throw ActionError("permission:location_background", "Localisation « Toujours autoriser » requise app fermée (onglet Actions)")
    }
    var loc: Location? = null
    try {
      val fused = LocationServices.getFusedLocationProviderClient(ctx)
      val cts = CancellationTokenSource()
      loc = try {
        Tasks.await(fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token), 20, TimeUnit.SECONDS)
      } catch (e: Exception) {
        cts.cancel()
        null
      } ?: Tasks.await(fused.lastLocation, 5, TimeUnit.SECONDS)
    } catch (e: Exception) {
      Log.w(Aura.TAG, "FusedLocation indisponible, repli LocationManager", e)
    }
    if (loc == null) {
      val lm = ctx.getSystemService(LocationManager::class.java)
      loc = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }
    val l = loc ?: throw ActionError("unavailable", "Position introuvable (GPS coupé ?)")
    val out = JSONObject().put("lat", l.latitude).put("lon", l.longitude).put("accuracy", l.accuracy.toDouble())
      .put("ts", iso(l.time))
    try {
      @Suppress("DEPRECATION")
      val a = Geocoder(ctx, Locale.FRANCE).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()
      if (a != null) out.put("address", (0..a.maxAddressLineIndex).joinToString(", ") { a.getAddressLine(it) })
    } catch (e: Exception) {
      // geocodeur hors ligne : la position suffit
    }
    return out
  }

  // ─── Etat, son, lampe ───────────────────────────────────────────────────

  private fun audio() = ctx.getSystemService(AudioManager::class.java)

  private val streams = mapOf(
    "media" to AudioManager.STREAM_MUSIC,
    "ring" to AudioManager.STREAM_RING,
    "alarm" to AudioManager.STREAM_ALARM,
    "notification" to AudioManager.STREAM_NOTIFICATION,
  )

  private fun pct(stream: Int): Int {
    val a = audio()
    val max = a.getStreamMaxVolume(stream).coerceAtLeast(1)
    return Math.round(a.getStreamVolume(stream) * 100f / max)
  }

  @SuppressLint("MissingPermission")
  private fun deviceStatus(): JSONObject {
    val battery = ContextCompat.registerReceiver(ctx, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val st = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    val network = when {
      caps == null -> "none"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
      else -> "other"
    }
    var ssid: Any = JSONObject.NULL
    if (network == "wifi" && Perms.location()) {
      @Suppress("DEPRECATION")
      val s = ctx.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
      if (s != null && s != "<unknown ssid>") ssid = s.trim('"')
    }
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val a = audio()
    return JSONObject()
      .put("battery", if (level >= 0) Math.round(level * 100f / scale) else JSONObject.NULL)
      .put("charging", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
      .put("network", network)
      .put("vpn", caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
      .put("wifi_ssid", ssid)
      .put("volume", JSONObject().apply { streams.forEach { (k, v) -> put(k, pct(v)) } })
      .put("ringer_mode", when (a.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "normal"
      })
      .put("dnd", nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
        nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
      .put("screen_on", ctx.getSystemService(PowerManager::class.java).isInteractive)
      .put("watch_connected", Aura.watch.connected())
      .put("watch_name", Aura.watch.stateJson().opt("name"))
      .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
      .put("android", Build.VERSION.RELEASE)
      .apply {
        // stockage (03/10, PROTOCOL.md §19.2) : octets libres / total de la partition des donnees
        PhoneContext.storage()?.let { (free, total) -> put("storage_free_bytes", free).put("storage_total_bytes", total) }
      }
  }

  private fun volumeSet(stream: String, percent: Int): JSONObject {
    val s = streams[stream] ?: throw ActionError("bad_params", "stream : media, ring, alarm ou notification")
    if (percent !in 0..100) throw ActionError("bad_params", "percent entre 0 et 100 attendu")
    val a = audio()
    val max = a.getStreamMaxVolume(s)
    try {
      a.setStreamVolume(s, Math.round(percent * max / 100f), 0)
    } catch (e: SecurityException) {
      throw ActionError("permission:dnd_access", "Android exige l'accès « Ne pas déranger » pour ce volume")
    }
    return JSONObject().put("stream", stream).put("percent", pct(s))
  }

  private fun dndSet(on: Boolean): JSONObject {
    Perms.require("dnd_access", Perms.dnd())
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
    return JSONObject().put("on", on)
  }

  private fun ringerMode(mode: String): JSONObject {
    val m = when (mode) {
      "normal" -> AudioManager.RINGER_MODE_NORMAL
      "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
      "silent" -> AudioManager.RINGER_MODE_SILENT
      else -> throw ActionError("bad_params", "mode : normal, vibrate ou silent")
    }
    try {
      audio().ringerMode = m
    } catch (e: SecurityException) {
      throw ActionError("permission:dnd_access", "Android exige l'accès « Ne pas déranger » pour ce mode")
    }
    return JSONObject().put("mode", mode)
  }

  private fun flashlight(on: Boolean): JSONObject {
    if (!Perms.hasFlash()) throw ActionError("unsupported", "Pas de flash sur cet appareil")
    val cam = ctx.getSystemService(CameraManager::class.java)
    val id = cam.cameraIdList.firstOrNull {
      val ch = cam.getCameraCharacteristics(it)
      ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
        ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } ?: cam.cameraIdList.firstOrNull { cam.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    ?: throw ActionError("unsupported", "Aucune caméra avec flash")
    cam.setTorchMode(id, on)
    return JSONObject().put("on", on)
  }

  // ─── Media ──────────────────────────────────────────────────────────────

  private fun mediaControl(cmd: String): JSONObject {
    val code = when (cmd) {
      "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
      "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
      "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
      "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
      "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
      else -> throw ActionError("bad_params", "cmd : play, pause, toggle, next ou previous")
    }
    val a = audio()
    a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
    a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    return JSONObject().put("cmd", cmd)
  }

  private fun mediaNow(): JSONObject {
    Perms.require("notification_listener", Perms.notifListener())
    val msm = ctx.getSystemService(MediaSessionManager::class.java)
    val sessions = msm.getActiveSessions(android.content.ComponentName(ctx, AuraNotificationListener::class.java))
    val s = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
      ?: return JSONObject().put("playing", false)
    val md = s.metadata
    return JSONObject()
      .put("playing", s.playbackState?.state == PlaybackState.STATE_PLAYING)
      .put("app", s.packageName)
      .put("title", md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: JSONObject.NULL)
      .put("artist", md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: JSONObject.NULL)
      .put("album", md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: JSONObject.NULL)
      .put("duration_s", md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.div(1000) ?: JSONObject.NULL)
      .put("position_s", s.playbackState?.position?.div(1000) ?: JSONObject.NULL)
  }

  // ─── Applis, liens, navigation ──────────────────────────────────────────

  private fun openApp(pkg: String?, name: String?): JSONObject {
    val pm = ctx.packageManager
    val target = pkg?.takeIf { it.isNotBlank() } ?: run {
      val q = Contacts.norm(name ?: throw ActionError("bad_params", "name ou package attendu"))
      val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName to Contacts.norm(it.loadLabel(pm).toString()) }
        .distinctBy { it.first }
      val exact = launchers.filter { it.second == q }
      val loose = launchers.filter { it.second.contains(q) }
      when {
        exact.size == 1 -> exact[0].first
        exact.isEmpty() && loose.size == 1 -> loose[0].first
        exact.isEmpty() && loose.isEmpty() -> throw ActionError("not_found", "Aucune appli nommée « $name »")
        else -> throw ActionError("ambiguous", "Plusieurs applis correspondent à « $name » : " +
          (exact.ifEmpty { loose }).take(8).joinToString(", ") { it.first })
      }
    }
    val i = pm.getLaunchIntentForPackage(target) ?: throw ActionError("not_found", "Appli non lançable : $target")
    return JSONObject().put("package", target).put("mode", Launcher.start(i, target))
  }

  private fun navigate(destination: String, mode: String?): JSONObject {
    val enc = Uri.encode(destination)
    val intent = if (mode == "transit") {
      Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$enc&travelmode=transit"))
    } else {
      val m = when (mode) { "walking" -> "w"; "bicycling" -> "b"; else -> "d" }
      Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$enc&mode=$m")).setPackage("com.google.android.apps.maps")
    }
    val resolved = if (intent.resolveActivity(ctx.packageManager) != null) intent
    else Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc"))
    return JSONObject().put("mode", Launcher.start(resolved, "Itinéraire : $destination"))
  }

  private fun clipboard() = ctx.getSystemService(ClipboardManager::class.java)

  private fun clipboardGet(): JSONObject {
    // Android 10+ : lecture refusee a une appli qui n'a pas le focus
    if (!Aura.appForeground) throw ActionError("unsupported", "Android interdit de lire le presse-papiers app en arrière-plan")
    val clip = clipboard().primaryClip
    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(ctx).toString() else ""
    return JSONObject().put("text", text)
  }

  private fun emailCompose(to: String, subject: String, body: String): JSONObject {
    val uri = Uri.parse("mailto:${Uri.encode(to)}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")
    val i = Intent(Intent.ACTION_SENDTO, uri)
      .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
      .putExtra(Intent.EXTRA_SUBJECT, subject)
      .putExtra(Intent.EXTRA_TEXT, body)
    val mode = Launcher.start(i, "Mail à $to")
    return JSONObject().put("mode", if (mode == "opened") "opened" else "notification")
  }
}
