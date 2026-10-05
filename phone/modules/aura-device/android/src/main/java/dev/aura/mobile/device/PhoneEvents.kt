package dev.aura.mobile.device

import android.Manifest
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.provider.CallLog
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Declencheurs (PROTOCOL.md §9.1) : le telephone previent Aura tout seul par `phone_event {kind, ...}`.
 *
 *  - appel manque : observateur du journal d'appels (READ_CALL_LOG), rattrape aussi a chaque contexte ;
 *  - appel entrant (`call_ringing`) : diffusion PHONE_STATE (READ_PHONE_STATE, + READ_CALL_LOG pour le numero) ; le
 *    bridge repond par une carte d'appel (CallCards.kt) ;
 *  - notification d'une app CHOISIE : `onNotificationPosted` du listener existant, titre et texte tronques ;
 *  - entree / sortie de zone, batterie basse (une fois par descente), chargeur : calcules a chaque
 *    `phone_context` (donc seulement bridge joignable ; rien n'allume le GPS pour ca) ;
 *  - notification retiree (`notif_removed`, 03/10) : paquet + raison, jamais de contenu, hors `events_wanted` (le bridge
 *    la journalise pour phone_watch.py) et jamais ecrite au Journal Aura (une ligne par notification noierait le reste).
 *
 * Ce qui remonte est du CONTENU DE TIERS : l'app ne l'interprete jamais, le bridge le traite comme donnee.
 * Tout se coupe dans les reglages (interrupteur general et un par type), la liste d'apps est vide par defaut,
 * et les paquets sensibles (UiGuard.blocked) ne remontent jamais. Anti-spam : EventGate, puis celui du bridge.
 */
object PhoneEvents {
  private const val PREF_SETTINGS = "events_settings"
  private const val PREF_CALL_TS = "ev_call_ts"
  private const val PREF_CHARGING = "ev_charging"
  private const val PREF_BATT_ARMED = "ev_batt_armed"
  private const val PREF_ZONES_IN = "ev_zones_in"
  private const val TITLE_MAX = 80
  private const val TEXT_MAX = 200
  private const val CALLS_MIN_GAP_MS = 60_000L

  @Volatile var unsupported = false  // bridge d'avant le §9.1 : bad_type, on se tait jusqu'a la reconnexion
  /** Bridge d'avant le 03/10 : `phone_event.kind` refuse pour notif_removed (bad_field), plus rien jusqu'a la reconnexion. */
  @Volatile var removedUnsupported = false
  @Volatile private var cached: EventSettings? = null
  private val gate = EventGate()
  private val latch by lazy { BatteryLatch(Aura.prefs.getBoolean(PREF_BATT_ARMED, true)) }
  private var observer: ContentObserver? = null
  private var lastCallPoll = 0L
  private val ring = RingTracker()  // thread Aura.io seulement
  private var phoneReceiver: BroadcastReceiver? = null
  private var ringGrace: ScheduledFuture<*>? = null
  @Volatile private var phoneStateMissingNoted = false

  // ─── Reglages ───────────────────────────────────────────────────────────

  fun settings(): EventSettings = cached ?: EventSettings.fromJson(Aura.prefs.getString(PREF_SETTINGS, null)).also { cached = it }

  fun settingsJson(): String = settings().toJson().toString()

  /** Enregistre (apres normalisation : paquets sensibles retires) et rend ce qui est reellement stocke. */
  fun setSettings(json: String): String {
    val s = EventSettings.fromJson(json)
    Aura.prefs.edit().putString(PREF_SETTINGS, s.toJson().toString()).apply()
    cached = s
    return s.toJson().toString()
  }

  /** Nouvelle connexion : le bridge a peut-etre ete mis a jour depuis. */
  fun onWelcome() {
    unsupported = false
    removedUnsupported = false
  }

  // ─── Cycle de vie (AuraService) ─────────────────────────────────────────

  fun start(ctx: android.content.Context) {
    if (observer != null) return
    val obs = object : ContentObserver(Aura.main) {
      override fun onChange(selfChange: Boolean) {
        Aura.io.execute { pollCalls(force = true) }
      }
    }
    try {
      ctx.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, obs)
      observer = obs
    } catch (e: Exception) {
      Log.w(Aura.TAG, "journal d'appels non observe (rattrapage a chaque contexte)", e)
    }
    registerPhoneState(ctx)
  }

  fun stop(ctx: android.content.Context) {
    phoneReceiver?.let { try { ctx.unregisterReceiver(it) } catch (e: Exception) { /* deja retire */ } }
    phoneReceiver = null
    observer?.let { try { ctx.contentResolver.unregisterContentObserver(it) } catch (e: Exception) { /* deja retire */ } }
    observer = null
  }

  // ─── Emission ───────────────────────────────────────────────────────────

  /**
   * Thread Aura.io. Passe les reglages, la pause, l'anti-spam, puis envoie ; `queue` : garde en file si hors ligne.
   * Chaque issue est ecrite au journal Aura (envoye, en file, non envoye, filtre, bloque, en pause).
   */
  private fun emit(kind: String, fingerprint: String, fields: JSONObject, ts: Long = System.currentTimeMillis(), queue: Boolean = false) {
    // notif_removed : ni Journal Aura (trop frequent), ni log par evenement (logcat), ni events_wanted (journal du bridge)
    val quiet = kind in EventGate.JOURNAL_KINDS
    if (!settings().allows(kind)) {
      if (!quiet) Journal.event(kind, fields, JournalLogic.BLOCKED, "réglage coupé")
      return
    }
    // Pause d'Aura `all` : plus aucun phone_event, et rien n'est mis de cote pour apres la reprise
    if (Pause.blocksEvents()) {
      if (quiet) return
      Journal.event(kind, fields, JournalLogic.PAUSED)
      Log.i(Aura.TAG, "phone_event $kind ecarte (Aura en pause)")
      return
    }
    if (unsupported || (quiet && removedUnsupported)) {
      if (!quiet) Journal.event(kind, fields, JournalLogic.BLOCKED, "bridge sans phone_event")
      return
    }
    // events_wanted (§11.14) : le bridge n'a aucune regle active pour ce type, rien ne part. L'evenement reste visible au journal.
    if (!quiet && !EventsWanted.allows(kind)) {
      Journal.event(kind, fields, JournalLogic.FILTERED, "aucune règle active")
      Usage.count("evt.filtre.$kind")
      Log.i(Aura.TAG, "phone_event $kind ecarte (aucune regle active)")
      return
    }
    if (!gate.allow(kind, fingerprint, System.currentTimeMillis())) {
      Usage.count("evt.antispam.$kind")
      if (quiet) return
      Journal.event(kind, fields, JournalLogic.FILTERED, "doublon ou trop fréquent")
      Log.i(Aura.TAG, "phone_event $kind ecarte (doublon ou trop frequent)")
      return
    }
    val msg = JSONObject(fields.toString()).put("type", "phone_event").put("kind", kind).put("ts", ts)
    val wasOnline = Aura.bridge.online()
    val sent = if (queue) { Aura.bridge.sendOrQueue(msg); true } else Aura.bridge.send(msg)
    val status = when {
      !sent -> JournalLogic.OFFLINE
      queue && !wasOnline -> JournalLogic.QUEUED
      else -> JournalLogic.SENT
    }
    if (sent) Usage.count("evt.envoye.$kind")
    if (quiet) return
    Journal.event(kind, fields, status)
    // jamais le contenu dans logcat : seulement le type
    Log.i(Aura.TAG, "phone_event $kind $status")
  }

  // ─── Appel entrant (sonnerie) ───────────────────────────────────────────

  private fun registerPhoneState(ctx: Context) {
    if (phoneReceiver != null) return
    val r = object : BroadcastReceiver() {
      override fun onReceive(c: Context, i: Intent) {
        val state = i.getStringExtra(TelephonyManager.EXTRA_STATE)
        // numero : fourni seulement avec READ_CALL_LOG, en seconde diffusion (RingTracker attend)
        @Suppress("DEPRECATION") val number = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        Aura.io.execute { onCallState(state, number) }
      }
    }
    // sans READ_PHONE_STATE Android ne livre jamais la diffusion : aucune sonnerie, aucune fiche, et rien ne le dirait
    if (!Perms.granted(Manifest.permission.READ_PHONE_STATE) && !phoneStateMissingNoted) {
      phoneStateMissingNoted = true  // une fois par processus : le journal ne doit pas se remplir a chaque redemarrage du service
      Journal.event("call_ringing", JSONObject(), JournalLogic.BLOCKED, "sonnerie non détectée : permission manquante (Téléphone)")
      Log.i(Aura.TAG, "call_ringing : sonnerie non detectee, READ_PHONE_STATE manquante")
    }
    try {
      ContextCompat.registerReceiver(ctx, r, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
      phoneReceiver = r
    } catch (e: Exception) {
      Log.w(Aura.TAG, "etat du telephone non ecoute (pas de carte d'appel)", e)
    }
  }

  /** Thread Aura.io. */
  fun onCallState(state: String?, number: String?) {
    when (ring.onState(state, number)) {
      RingTracker.Decision.EMIT -> { ringGrace?.cancel(false); emitRinging(ring.number) }
      RingTracker.Decision.WAIT -> {
        ringGrace?.cancel(false)
        ringGrace = Aura.timer.schedule(Runnable {
          Aura.io.execute { if (ring.onGraceElapsed() == RingTracker.Decision.EMIT) emitRinging(ring.number) }
        }, RingTracker.GRACE_MS, TimeUnit.MILLISECONDS)
      }
      RingTracker.Decision.NONE -> if (state != RingTracker.RINGING) ringGrace?.cancel(false)
    }
  }

  private fun emitRinging(number: String?) {
    if (number == null && !Perms.granted(Manifest.permission.READ_CALL_LOG)) {
      // sans le journal d'appels Android ne donne jamais le numero : on le dit au lieu d'envoyer un appel muet
      if (settings().allows("call_ringing") && !Pause.blocksEvents()) {
        Journal.event("call_ringing", JSONObject(), JournalLogic.BLOCKED, "journal d'appels non autorisé : numéro illisible")
      }
      return
    }
    val f = JSONObject()
    if (!number.isNullOrBlank()) f.put("number", clipEventText(number, 30))
    emit("call_ringing", number ?: "-", f)
  }

  // ─── Appels manques ─────────────────────────────────────────────────────

  /** Thread Aura.io. Les appels manques depuis le dernier releve ; le premier passage ne fait que poser le repere. */
  fun pollCalls(force: Boolean = false) {
    val now = System.currentTimeMillis()
    if (!force && now - lastCallPoll < CALLS_MIN_GAP_MS) return
    lastCallPoll = now
    if (!Perms.granted(Manifest.permission.READ_CALL_LOG)) return
    val last = Aura.prefs.getLong(PREF_CALL_TS, -1L)
    if (Pause.blocksEvents()) {
      // les appels manques PENDANT la pause ne sont pas rejoues a la reprise : le repere avance sans rien envoyer
      if (last >= 0) Aura.prefs.edit().putLong(PREF_CALL_TS, now).apply()
      return
    }
    if (last < 0) {
      Aura.prefs.edit().putLong(PREF_CALL_TS, now).apply()  // pas de rattrapage de l'historique
      return
    }
    var newest = last
    try {
      Aura.app.contentResolver.query(CallLog.Calls.CONTENT_URI,
        arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE),
        "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ?", arrayOf(CallLog.Calls.MISSED_TYPE.toString(), last.toString()),
        "${CallLog.Calls.DATE} ASC")?.use { c ->
        var n = 0
        while (c.moveToNext() && n < 10) {
          n++
          val number = c.getString(0) ?: ""
          val date = c.getLong(2)
          newest = maxOf(newest, date)
          var name = c.getString(1)
          if (name.isNullOrBlank() && number.isNotEmpty() && Perms.granted(Manifest.permission.READ_CONTACTS)) {
            name = try { Contacts.nameFor(number) } catch (e: Exception) { null }
          }
          val f = JSONObject().put("number", clipEventText(number, 30))
          if (!name.isNullOrBlank()) f.put("name", clipEventText(name, 80))
          emit("missed_call", "$number|${name ?: ""}", f, ts = date, queue = true)
        }
      }
    } catch (e: Exception) {
      Log.w(Aura.TAG, "journal d'appels illisible", e)
      return
    }
    if (newest > last) Aura.prefs.edit().putLong(PREF_CALL_TS, newest).apply()
  }

  // ─── Notifications d'apps choisies ──────────────────────────────────────

  /** Thread du listener : filtres bon marche ici, l'envoi part sur Aura.io. */
  fun onNotification(sbn: StatusBarNotification) {
    val s = settings()
    val pkg = sbn.packageName
    if (!s.allows("notification") || pkg !in s.notifApps || UiGuard.blocked(pkg) || pkg == Aura.app.packageName) return
    val n = sbn.notification
    if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0 || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
    if (n.visibility == Notification.VISIBILITY_SECRET) return  // l'app elle-meme la juge trop privee pour l'ecran verrouille
    val ex = n.extras
    val title = clipEventText(ex.getCharSequence(Notification.EXTRA_TITLE), TITLE_MAX)
    val text = clipEventText(ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT), TEXT_MAX)
    if (title.isEmpty() && text.isEmpty()) return
    val label = try {
      val pm = Aura.app.packageManager
      pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }
    val f = JSONObject().put("app", pkg).put("app_name", clipEventText(label, 60))
    if (title.isNotEmpty()) f.put("title", title)
    if (text.isNotEmpty()) f.put("text", text)
    Aura.io.execute { emit("notification", "$pkg|$title|$text", f) }
  }

  // ─── Notifications retirees (03/10) ─────────────────────────────────────

  /**
   * Thread du listener. Toutes les apps (pas seulement la liste choisie : rien du contenu ne part), sauf Aura elle-meme,
   * les apps sensibles (UiGuard.blocked), les resumes de groupe et les notifications permanentes (lecteur, navigation,
   * service de premier plan : leur retrait ne dit rien de l'utilisateur). Hors ligne : perdue, jamais mise en file.
   */
  fun onNotificationRemoved(sbn: StatusBarNotification, reason: Int, channelId: String?) {
    val s = settings()
    val pkg = sbn.packageName
    if (!s.allows("notif_removed") || removedUnsupported || pkg == Aura.app.packageName || UiGuard.blocked(pkg)) return
    val n = sbn.notification
    val permanent = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY
    if ((n.flags and permanent) != 0) return
    val label = try {
      val pm = Aura.app.packageManager
      pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }
    val now = System.currentTimeMillis()
    val f = NotifRemoval.fields(pkg, label, reason, n.category, channelId, sbn.isClearable, sbn.postTime, sbn.key, now) ?: return
    Aura.io.execute { emit("notif_removed", "$pkg|${f.optString("nid")}|${f.optString("reason")}", f, ts = now) }
  }

  // ─── Contexte : chargeur, batterie, zones, rattrapage des appels ───────

  /** Thread Aura.io, appele par PhoneContext.evaluate avec l'instantane qu'il vient de mesurer. */
  fun onSnapshot(snap: JSONObject) {
    try {
      charger(snap)
      battery(snap)
      zones(snap)
      pollCalls()
    } catch (e: Exception) {
      Log.w(Aura.TAG, "declencheurs: erreur", e)
    }
  }

  private fun charger(snap: JSONObject) {
    if (!snap.has("charging")) return
    val now = if (snap.optBoolean("charging")) 1 else 0
    val prev = Aura.prefs.getInt(PREF_CHARGING, -1)
    if (prev == now) return
    Aura.prefs.edit().putInt(PREF_CHARGING, now).apply()
    if (prev < 0) return  // premiere observation : on apprend, on ne previent pas
    val f = JSONObject().put("connected", now == 1)
    if (snap.has("battery")) f.put("percent", snap.optInt("battery"))
    emit("charger", (now == 1).toString(), f)
  }

  private fun battery(snap: JSONObject) {
    if (!snap.has("battery")) return
    val pct = snap.optInt("battery")
    val before = latch.armed
    val fire = latch.step(pct, snap.optBoolean("charging"))
    if (latch.armed != before) Aura.prefs.edit().putBoolean(PREF_BATT_ARMED, latch.armed).apply()
    if (fire) emit("battery_low", "-", JSONObject().put("percent", pct))
  }

  private fun zones(snap: JSONObject) {
    val loc = snap.optJSONObject("location") ?: return
    val zones = ZoneTracker.parseZones(PhoneContext.zones())
    val raw = Aura.prefs.getString(PREF_ZONES_IN, null)
    val prev: Set<String>? = raw?.let { r -> try { JSONArray(r).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } } catch (e: Exception) { null } }
    val step = ZoneTracker.step(prev, loc.optDouble("lat"), loc.optDouble("lon"), loc.optDouble("accuracy", 0.0),
      loc.optLong("ts", 0L), zones, System.currentTimeMillis()) ?: return
    if (step.inside != prev) Aura.prefs.edit().putString(PREF_ZONES_IN, JSONArray(step.inside.toList()).toString()).apply()
    for ((transition, zone) in step.events) {
      emit("zone", "$transition|${zone.lowercase()}", JSONObject().put("transition", transition).put("zone", clipEventText(zone, 30)))
    }
  }
}
