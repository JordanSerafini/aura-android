package dev.aura.mobile.device

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/** Erreur d'action : `code` part tel quel dans action_result.error (refused, timeout, unsupported, permission:<nom>…). */
class ActionError(val code: String, message: String, val extra: JSONObject? = null) : Exception(message)

/**
 * Etat partage par tout le processus : service, module Expo, recepteurs, services Data Layer.
 * Tout ce qui doit marcher app fermee vit ici, pas dans le module (qui n'existe que si le JS tourne).
 */
object Aura {
  const val TAG = "AuraDevice"
  const val DEFAULT_URL = "wss://example.invalid/ws"
  const val DEFAULT_POSTE = "s22-natif"

  lateinit var app: Context
    private set
  lateinit var prefs: SharedPreferences
    private set

  val main = Handler(Looper.getMainLooper())
  val pool: ExecutorService = Executors.newCachedThreadPool()
  val io: ExecutorService = Executors.newSingleThreadExecutor()  // messages du bridge, dans l'ordre
  val wearQ: ExecutorService = Executors.newSingleThreadExecutor()  // messages vers la montre, dans l'ordre
  val timer: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

  @Volatile var listener: ((String, String) -> Unit)? = null
  @Volatile var appForeground = false
  @Volatile var serviceRunning = false
  /** Session vocale Talk en cours (ecran TalkScreen), null sinon. */
  @Volatile var talk: TalkSession? = null

  val bridge by lazy { BridgeClient() }
  val watch by lazy { WatchLink() }
  val relay by lazy { Relay(BridgeIo) }
  val confirms by lazy { ConfirmManager() }
  val executor by lazy { ActionExecutor() }

  @Synchronized
  fun init(ctx: Context) {
    if (::app.isInitialized) return
    app = ctx.applicationContext
    prefs = app.getSharedPreferences("aura_device", Context.MODE_PRIVATE)
    Notifs.createChannels(app)
    Pause.start()
    WatchStatus.start()
  }

  // ─── Configuration ──────────────────────────────────────────────────────

  val url: String get() = prefs.getString("url", null) ?: DEFAULT_URL
  val token: String get() = prefs.getString("token", null) ?: ""
  val poste: String get() = prefs.getString("poste", null) ?: DEFAULT_POSTE
  /**
   * Version REELLE de l'APK installee (PackageManager), plus celle que le JS a passee a configure() : jusqu'au 03/10 le
   * hello disait « 1.0.0 » quelle que soit l'APK. Repli sur les prefs si le paquet est illisible (jamais vu).
   */
  val version: String get() = BuildInfo.versionName ?: prefs.getString("version", null) ?: "1.0.0"
  val versionCode: Long get() = BuildInfo.versionCode
  val buildId: String get() = BuildInfo.buildId

  fun info(): JSONObject = JSONObject()
    .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
    .put("android", Build.VERSION.RELEASE)
    .put("sdk", Build.VERSION.SDK_INT)
    .put("app_version", version)
    .put("version_code", versionCode)
    .put("build", buildId)
    .put("watch_connected", watch.connected())

  // ─── Evenements vers le JS ──────────────────────────────────────────────

  fun emit(event: String, json: String) {
    val l = listener ?: return
    main.post { try { l(event, json) } catch (e: Exception) { Log.w(TAG, "evenement $event perdu", e) } }
  }

  fun stateJson(): String = JSONObject()
    .put("service", serviceRunning)
    .put("bridge", bridge.status)
    .put("bridgeDetail", bridge.detail)
    .put("device", bridge.deviceName ?: JSONObject.NULL)
    .put("settings", bridge.settings ?: JSONObject.NULL)
    .put("phoneConfirm", bridge.phoneConfirm() ?: JSONObject.NULL)
    .put("capsSent", bridge.capsSent())
    .put("watch", watch.stateJson())
    .toString()

  fun emitState() {
    emit("onState", stateJson())
    AuraService.refreshNotification()
    WatchStatus.push()  // dedoublonne : seule une vraie difference part vers la montre
  }

  // ─── Journal (unifie : evenements, actions, gestes : Journal.kt) ─────────

  fun addLog(entry: JSONObject) = Journal.add(entry)

  fun logJson(): String = Journal.json()

  fun clearLog() = Journal.clear()

  // ─── Reglages montre (voice_mode + horaires, §3) ────────────────────────

  fun watchSettings(): JSONObject {
    val raw = prefs.getString("watch_settings", null)
    val def = JSONObject()
      .put("voice_mode", "auto")
      .put("work_hours", JSONObject().put("days", JSONArray(listOf(1, 2, 3, 4, 5))).put("start", "08:30").put("end", "17:30"))
    if (raw == null) return def
    return try { JSONObject(raw) } catch (e: Exception) { def }
  }

  /** Reponse lue a voix haute sur la montre ? auto = oui, sauf en semaine aux heures de travail. */
  fun voiceWanted(now: LocalDateTime = LocalDateTime.now()): Boolean {
    val s = watchSettings()
    return when (s.optString("voice_mode", "auto")) {
      "voice" -> true
      "text" -> false
      else -> !inWorkHours(s.optJSONObject("work_hours"), now)
    }
  }

  fun inWorkHours(wh: JSONObject?, now: LocalDateTime): Boolean {
    if (wh == null) return false
    val days = wh.optJSONArray("days") ?: return false
    val today = now.dayOfWeek.value
    val isDay = (0 until days.length()).any { days.optInt(it) == today }
    if (!isDay) return false
    return try {
      val start = LocalTime.parse(wh.optString("start", "08:30"))
      val end = LocalTime.parse(wh.optString("end", "17:30"))
      val t = now.toLocalTime()
      !t.isBefore(start) && t.isBefore(end)
    } catch (e: Exception) {
      false
    }
  }
}

/**
 * versionName / versionCode de l'APK et identifiant de build (meta-data `dev.aura.mobile.BUILD_ID` posee par
 * plugins/withAuraAndroid.js : date + commit, « -dirty » si l'arbre n'etait pas commite). Lu une fois par processus.
 */
object BuildInfo {
  private const val META = "dev.aura.mobile.BUILD_ID"

  private val pkg by lazy {
    try {
      Aura.app.packageManager.getPackageInfo(Aura.app.packageName, 0)
    } catch (e: Exception) {
      null
    }
  }

  val versionName: String? get() = pkg?.versionName
  @Suppress("DEPRECATION")
  val versionCode: Long get() = pkg?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() } ?: 0L
  val buildId: String by lazy {
    try {
      val ai = Aura.app.packageManager.getApplicationInfo(Aura.app.packageName, android.content.pm.PackageManager.GET_META_DATA)
      ai.metaData?.getString(META)?.take(60) ?: "inconnu"
    } catch (e: Exception) {
      "inconnu"
    }
  }
}

/** Chaine d'un champ JSON, null si absent ou JSON null (optString rend "null" dans ce cas). */
fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)
