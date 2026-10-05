package dev.aura.mobile.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * `phone_context` (PROTOCOL.md §7.3) : etat du telephone envoye par la connexion native a chaque
 * welcome, puis sur changement notable, au plus une fois toutes les 5 min, sauf batterie < 15 % ou
 * branchement/debranchement (tout de suite).
 *
 * Changement notable : charge, reseau, casque, NPD, sonnerie, batterie qui passe une dizaine, prochain
 * RDV, deplacement de plus de 300 m. L'ecran allume/eteint est envoye mais ne declenche rien seul (il
 * bascule des dizaines de fois par jour).
 */
object PhoneContext {
  private const val MIN_INTERVAL_MS = 5 * 60_000L
  private const val POLL_MS = 5 * 60_000L
  private const val MOVE_M = 300f
  const val LOW_BATTERY = 15

  @Volatile private var started = false
  @Volatile var unsupported = false  // bridge d'avant le §7.3 : bad_type, on se tait jusqu'a la reconnexion
  private var lastSent: JSONObject? = null
  private var lastSentAt = 0L
  private var deferred: ScheduledFuture<*>? = null
  private var poller: ScheduledFuture<*>? = null
  private var netCb: ConnectivityManager.NetworkCallback? = null
  private var audioCb: AudioDeviceCallback? = null

  // lazy : l'objet reste chargeable dans les tests JVM (notable, mergeZone) sans instancier d'API Android
  private val receiver by lazy {
    object : BroadcastReceiver() {
      override fun onReceive(c: Context, i: Intent) {
        val urgent = i.action == Intent.ACTION_POWER_CONNECTED || i.action == Intent.ACTION_POWER_DISCONNECTED
        // ecran allume : socket du bridge relancee tout de suite. Le delai de reconnexion (Aura.timer) compte en temps
        // MONOTONE, qui s'arrete pendant le sommeil profond : un « nouvel essai dans 60 s » pose au debut d'un Doze
        // partait des heures plus tard (journal du bridge : s22 natif absent 2,7 h le 29/09 des 14:54, 4,7 h le 30/09 et le
        // 01/10). kick() ne fait rien si la socket est ouverte ou en cours, ni apres un vrai refus du jeton.
        if (i.action == Intent.ACTION_SCREEN_ON) Aura.io.execute { Aura.bridge.kick() }
        Aura.io.execute { evaluate(urgent = urgent) }
      }
    }
  }

  fun start(ctx: Context) {
    if (started) return
    started = true
    val f = IntentFilter().apply {
      addAction(Intent.ACTION_BATTERY_CHANGED)
      addAction(Intent.ACTION_POWER_CONNECTED)
      addAction(Intent.ACTION_POWER_DISCONNECTED)
      addAction(AudioManager.ACTION_HEADSET_PLUG)
      addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
      addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
      addAction(Intent.ACTION_SCREEN_ON)
      addAction(Intent.ACTION_SCREEN_OFF)
    }
    ContextCompat.registerReceiver(ctx, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { Aura.io.execute { evaluate() } }
      override fun onLost(network: Network) { Aura.io.execute { evaluate() } }
    }
    try { cm.registerDefaultNetworkCallback(cb); netCb = cb } catch (e: Exception) { Log.w(Aura.TAG, "reseau non suivi", e) }
    val acb = object : AudioDeviceCallback() {
      override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { Aura.io.execute { evaluate() } }
      override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { Aura.io.execute { evaluate() } }
    }
    ctx.getSystemService(AudioManager::class.java).registerAudioDeviceCallback(acb, Aura.main)
    audioCb = acb
    poller = Aura.timer.scheduleWithFixedDelay({ Aura.io.execute { evaluate() } }, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
  }

  fun stop(ctx: Context) {
    if (!started) return
    started = false
    try { ctx.unregisterReceiver(receiver) } catch (e: Exception) { /* deja retire */ }
    netCb?.let { try { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (e: Exception) { } }
    netCb = null
    audioCb?.let { ctx.getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(it) }
    audioCb = null
    poller?.cancel(false)
    deferred?.cancel(false)
  }

  /** Nouvelle connexion (welcome) : le bridge a oublie, on renvoie tout de suite. */
  fun onWelcome() {
    unsupported = false
    Aura.io.execute {
      lastSent = null
      evaluate(force = true)
    }
  }

  /** Thread Aura.io uniquement. */
  private fun evaluate(force: Boolean = false, urgent: Boolean = false) {
    if (!started || unsupported || !Aura.bridge.online()) return
    val now = System.currentTimeMillis()
    val snap = try { snapshot() } catch (e: Exception) {
      Log.w(Aura.TAG, "phone_context illisible", e)
      return
    }
    PhoneEvents.onSnapshot(snap)  // declencheurs (§9.1) : chargeur, batterie basse, zones, appels manques
    val prev = lastSent
    val reason = if (force || prev == null) "initial" else notable(prev, snap)
    if (reason == null) return
    val batt = snap.optInt("battery", 100)
    val isUrgent = urgent || reason == "charging" || (reason == "battery" && batt < LOW_BATTERY)
    if (!force && !isUrgent && now - lastSentAt < MIN_INTERVAL_MS) {
      // trop tot : un seul envoi differe, avec l'etat du moment ou il partira
      if (deferred == null || deferred!!.isDone) {
        deferred = Aura.timer.schedule({ Aura.io.execute { evaluate(force = true) } },
          lastSentAt + MIN_INTERVAL_MS - now, TimeUnit.MILLISECONDS)
      }
      return
    }
    deferred?.cancel(false)
    if (Aura.bridge.send(JSONObject(snap.toString()).put("type", "phone_context"))) {
      lastSent = snap
      lastSentAt = now
      // journal local sans les coordonnees (logcat reste sur le telephone, mais inutile d'y laisser la position)
      val shown = JSONObject(snap.toString()).apply { if (has("location")) put("location", "…") }
      Log.i(Aura.TAG, "phone_context envoye ($reason) : $shown")
    }
  }

  /** Motif du changement notable, null s'il n'y en a pas (logique pure, testee en JVM). */
  fun notable(prev: JSONObject, cur: JSONObject): String? {
    // tz (03/10, mode voyage du bridge : desktop_bridge/fuseau.py) : arriver a Bangkok est un changement notable
    for (k in listOf("charging", "network", "headphones", "dnd", "ringer", "tz")) {
      if (prev.opt(k)?.toString() != cur.opt(k)?.toString()) return if (k == "charging") "charging" else k
    }
    val pb = prev.optInt("battery", -1)
    val cb = cur.optInt("battery", -1)
    if (pb != cb && (pb / 10 != cb / 10 || (cb < LOW_BATTERY && pb != cb))) return "battery"
    val pe = prev.optJSONObject("next_event")?.toString()
    val ce = cur.optJSONObject("next_event")?.toString()
    if (pe != ce) return "next_event"
    val pl = prev.optJSONObject("location")
    val cl = cur.optJSONObject("location")
    if ((pl == null) != (cl == null)) return "location"
    if (pl != null && cl != null) {
      if (meters(pl.getDouble("lat"), pl.getDouble("lon"), cl.getDouble("lat"), cl.getDouble("lon")) > MOVE_M) return "location"
    }
    return null
  }

  /** Distance haversine en metres (pas Location.distanceBetween : testable en JVM). */
  fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2).let { it * it } +
      Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
    return 2 * r * Math.asin(Math.sqrt(a))
  }

  // ─── Mesures ────────────────────────────────────────────────────────────

  fun snapshot(): JSONObject {
    val ctx = Aura.app
    val bat = ContextCompat.registerReceiver(ctx, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val am = ctx.getSystemService(AudioManager::class.java)
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val out = JSONObject()
    if (level >= 0) out.put("battery", Math.round(level * 100f / scale))
    out.put("charging", plugged)
      .put("network", network())
      .put("headphones", headphones(am))
      .put("dnd", nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
        nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
      .put("ringer", when (am.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "normal"
      })
      .put("screen_on", ctx.getSystemService(PowerManager::class.java).isInteractive)
      .put("ts", System.currentTimeMillis())
      // fuseau du telephone (nom IANA) : le bridge en deduit l'heure locale de l'utilisateur en voyage ; ignore par un bridge plus ancien
      .put("tz", ZoneId.systemDefault().id)
    // stockage (03/10) : pas un changement « notable » (il bouge sans cesse), juste joint a chaque envoi
    storage()?.let { (free, total) -> out.put("storage_free_bytes", free).put("storage_total_bytes", total) }
    lastLocation()?.let { l ->
      out.put("location", JSONObject().put("lat", l.latitude).put("lon", l.longitude)
        .put("accuracy", l.accuracy.toDouble()).put("ts", l.time))
    }
    nextEvent()?.let { out.put("next_event", it) }
    return out
  }

  /** Espace de la partition des donnees (celle des apps et des photos) : (libre, total) en octets, null si illisible. */
  fun storage(): Pair<Long, Long>? = try {
    val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
    val total = st.totalBytes
    if (total > 0) st.availableBytes to total else null
  } catch (e: Exception) {
    null
  }

  private fun network(): String {
    val cm = Aura.app.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
    return when {
      caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
      else -> "other"
    }
  }

  private val HEADPHONES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID,
  )

  private fun headphones(am: AudioManager): Boolean =
    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONES }

  /** Derniere position connue, sans allumer le GPS (null sans permission). */
  @SuppressLint("MissingPermission")
  private fun lastLocation(): Location? {
    if (!Perms.location()) return null
    try {
      val l = Tasks.await(LocationServices.getFusedLocationProviderClient(Aura.app).lastLocation, 3, TimeUnit.SECONDS)
      if (l != null) return l
    } catch (e: Exception) { /* Play services absent ou refus en arriere-plan */ }
    return try {
      val lm = Aura.app.getSystemService(LocationManager::class.java)
      lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    } catch (e: Exception) { null }
  }

  /** Prochain evenement de l'agenda dans les 24 h (hors journees entieres), null sans permission. */
  private fun nextEvent(): JSONObject? {
    if (!Perms.granted(Manifest.permission.READ_CALENDAR)) return null
    val now = System.currentTimeMillis()
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
    android.content.ContentUris.appendId(uri, now)
    android.content.ContentUris.appendId(uri, now + 24 * 3_600_000L)
    return try {
      Aura.app.contentResolver.query(uri.build(),
        arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
        "${CalendarContract.Instances.VISIBLE} = 1 AND ${CalendarContract.Instances.ALL_DAY} = 0 AND " +
          "${CalendarContract.Instances.BEGIN} >= ?", arrayOf(now.toString()),
        "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
        if (!c.moveToFirst()) null
        else JSONObject().put("title", (c.getString(0) ?: "").take(60))
          .put("start", OffsetDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(1)), ZoneId.systemDefault()).toString())
      }
    } catch (e: Exception) { null }
  }

  // ─── Zones (Maison / Travail) ───────────────────────────────────────────

  /** Zones connues du bridge (message settings), [] s'il n'en a pas encore. */
  fun zones(): JSONArray {
    val s = Aura.bridge.settings ?: return JSONArray()
    return s.optJSONArray("phone_zones") ?: s.optJSONObject("values")?.optJSONArray("phone_zones") ?: JSONArray()
  }

  /**
   * « Definir ma position actuelle comme <nom> » : position precise, fusion avec les zones existantes
   * (meme nom = remplacee), envoi settings_set.phone_zones. Bloquant (jusqu'a 20 s de GPS).
   */
  @SuppressLint("MissingPermission")
  fun setZoneHere(name: String, radiusM: Int = 150): JSONObject {
    val clean = name.trim().replace(Regex("\\s+"), " ")
    if (clean.isEmpty() || clean.length > 30) throw ActionError("bad_params", "Nom de zone : 1 à 30 caractères")
    Perms.require("location", Perms.location())
    if (!Aura.bridge.online()) throw ActionError("unavailable", "Serveur injoignable : réessaie une fois connecté")
    var loc: Location? = null
    try {
      val cts = CancellationTokenSource()
      loc = Tasks.await(LocationServices.getFusedLocationProviderClient(Aura.app)
        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token), 20, TimeUnit.SECONDS)
    } catch (e: Exception) { /* repli derniere position */ }
    val l = loc ?: lastLocation() ?: throw ActionError("unavailable", "Position introuvable (GPS coupé ?)")
    val zones = mergeZone(zones(), clean, l.latitude, l.longitude, radiusM.coerceIn(50, 5000).toDouble())
    if (!Aura.bridge.send(JSONObject().put("type", "settings_set").put("phone_zones", zones))) {
      throw ActionError("unavailable", "Envoi impossible : connexion perdue")
    }
    return JSONObject().put("name", clean).put("lat", l.latitude).put("lon", l.longitude)
      .put("accuracy", l.accuracy.toDouble()).put("radius_m", radiusM).put("zones", zones.length())
  }

  /** Fusion pure (testee en JVM) : remplace la zone de meme nom (casse ignoree), sinon l'ajoute (10 max). */
  fun mergeZone(existing: JSONArray, name: String, lat: Double, lon: Double, radius: Double): JSONArray {
    val out = JSONArray()
    for (i in 0 until existing.length()) {
      val z = existing.optJSONObject(i) ?: continue
      if (z.optString("name").trim().equals(name, ignoreCase = true)) continue
      out.put(JSONObject().put("name", z.optString("name")).put("lat", z.optDouble("lat"))
        .put("lon", z.optDouble("lon")).put("radius_m", z.optDouble("radius_m", 150.0)))
    }
    val zone = JSONObject().put("name", name).put("lat", lat).put("lon", lon).put("radius_m", radius)
    val list = (0 until out.length()).map { out.getJSONObject(it) }.takeLast(9) + zone
    return JSONArray(list)
  }
}
