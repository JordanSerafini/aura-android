package dev.aura.mobile.device

import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * WebSocket vers desktop_bridge (protocole de pwa/app.js + ajouts de docs/PROTOCOL.md §1).
 * Reconnexion a backoff exponentiel plafonne a 60 s. UN SEUL ping : celui d'OkHttp (trame ping du protocole, 25 s),
 * dont le defaut de pong ferme la socket (onFailure -> backoff -> reconnexion) : c'est la detection de socket mort.
 * Avant le 01/10 un ping applicatif a 30 s et un controle de silence de 95 s doublaient ce mecanisme, plus lentement.
 */
class BridgeClient {
  companion object {
    const val CLOSE_BAD_TOKEN = 4401
    private const val BACKOFF_MAX_MS = 60_000L
    /** Trame ping du protocole WebSocket ; sans pong a l'intervalle suivant, OkHttp ferme la socket. */
    private const val PING_S = 25L
    private const val PREF_OUTBOX = "bridge_outbox"
    private const val OUTBOX_MAX = 20
  }

  private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .pingInterval(PING_S, TimeUnit.SECONDS)
    .build()

  @Volatile var status = "stopped"  // stopped | connecting | online | offline | rejected
    private set
  @Volatile var detail = ""
    private set
  @Volatile var deviceName: String? = null
    private set
  @Volatile var settings: JSONObject? = null
    private set
  /** Dernier message recu du bridge (epoch ms) : la veille (BridgeWatchdog) y lit la reponse a son ping. */
  @Volatile var lastRxAt = 0L
    private set

  private val lock = Any()
  private var ws: WebSocket? = null
  private var gen = 0
  private var wanted = false
  private var backoffMs = 0L
  private var retry: ScheduledFuture<*>? = null
  @Volatile private var lastCaps: String? = null
  private val badToken = BadTokenCounter()
  /** Messages que le bridge a refuses en `bad_type` (bridge d'avant le 01/10 soir) : plus renvoyes jusqu'a la reconnexion. */
  private val unsupportedMsgs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private var netCallback: ConnectivityManager.NetworkCallback? = null

  fun online() = status == "online"
  fun capsSent(): Boolean = lastCaps != null

  fun start() {
    synchronized(lock) {
      if (wanted && ws != null) return
      wanted = true
      if (status == "rejected" || status == "stopped") status = "offline"
      backoffMs = 0
      badToken.reset()
    }
    registerNetwork()
    connect()
  }

  fun stop() {
    synchronized(lock) {
      wanted = false
      retry?.cancel(false)
      gen++
      ws?.close(1000, "arret")
      ws = null
      status = "stopped"
      detail = ""
      lastCaps = null
    }
    BridgeWatchdog.cancel()
    unregisterNetwork()
    WatchConvs.bridgeDown()
    Aura.relay.bridgeDown()
    LiveUpdate.clearAll()
    Aura.emitState()
  }

  /** Reconnexion immediate (reseau revenu, app au premier plan). Au premier plan, retente meme apres un refus. */
  fun kick(foreground: Boolean = false) {
    synchronized(lock) {
      if (!wanted || ws != null) return
      if (status == "rejected") {
        if (!foreground) return
        status = "offline"
        badToken.reset()
      }
      backoffMs = 0
      retry?.cancel(false)
    }
    connect()
  }

  private fun connect() {
    val token = Aura.token
    synchronized(lock) {
      if (!wanted || ws != null) return
      if (token.isEmpty()) {
        status = "offline"
        detail = "Aucun jeton appareil (build sans AURA_MOBILE_DEVICE_TOKEN ?)"
        Aura.emitState()
        return
      }
      status = "connecting"
      detail = ""
      val myGen = ++gen
      val req = Request.Builder().url(Aura.url).build()
      ws = client.newWebSocket(req, Listener(myGen))
    }
    Aura.emitState()
  }

  private inner class Listener(val myGen: Int) : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
      val hello = JSONObject()
        .put("type", "hello")
        .put("token", Aura.token)
        .put("poste", Aura.poste)
        .put("os", "android-native")
        .put("version", Aura.version)
        // champs ajoutes le 03/10 (PROTOCOL.md §19.1) : ignores par un bridge plus ancien (champs inconnus du hello)
        .put("version_code", Aura.versionCode)
        .put("build", Aura.buildId)
      webSocket.send(hello.toString())
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
      if (myGen != gen) return
      lastRxAt = System.currentTimeMillis()
      Aura.io.execute {
        try {
          handle(JSONObject(text))
        } catch (e: Exception) {
          Log.w(Aura.TAG, "message du bridge non traite", e)
        }
      }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      webSocket.close(code, null)
      down(myGen, code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = down(myGen, code, reason)

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
      down(myGen, response?.code ?: -1, t.message ?: t.javaClass.simpleName)
  }

  private fun down(myGen: Int, code: Int, reason: String) {
    synchronized(lock) {
      if (myGen != gen) return
      gen++
      ws = null
      lastCaps = null
      // 4401 aussi pour un hello en retard ou illisible : « rejected » seulement au 3e d'affilee
      if (badToken.onClose(code)) {
        status = "rejected"
        detail = "Jeton refusé ou appareil révoqué"
        BridgeWatchdog.cancel()
      } else if (wanted) {
        status = "offline"
        backoffMs = if (backoffMs == 0L) 1000L else minOf(backoffMs * 2, BACKOFF_MAX_MS)
        val delay = backoffMs + Random.nextLong(0, 500)
        val why = if (code == CLOSE_BAD_TOKEN) "Refusé par le bridge (${badToken.count}/${BadTokenCounter.MAX})" else reason.take(120)
        detail = "$why · nouvel essai dans ${delay / 1000} s"
        retry?.cancel(false)
        retry = Aura.timer.schedule(Runnable { connect() }, delay, TimeUnit.MILLISECONDS)
        // le minuteur ci-dessus s'arrete en sommeil profond : alarme en temps reel par-dessus (BridgeWatchdog, 03/10)
        BridgeWatchdog.onOffline()
      }
    }
    Log.i(Aura.TAG, "bridge deconnecte ($code) : $reason")
    WatchConvs.bridgeDown()
    Aura.relay.bridgeDown()
    LiveUpdate.clearAll()
    Aura.emitState()
  }

  /** Socket jugee morte par la veille : on la coupe net, onFailure -> down() -> reconnexion habituelle. */
  fun recycle(why: String) {
    val socket = synchronized(lock) { ws } ?: return
    Log.i(Aura.TAG, "socket relancee : $why")
    socket.cancel()
  }

  fun send(obj: JSONObject): Boolean {
    val socket = synchronized(lock) { if (status == "online") ws else null } ?: return false
    return socket.send(obj.toString())
  }

  /** Envoi, ou file (prefs, 20 au plus) rejouee au prochain welcome : actions des boutons de notification. */
  fun sendOrQueue(obj: JSONObject) {
    if (send(obj)) return
    synchronized(outboxLock) {
      val q = outbox()
      q.put(obj)
      while (q.length() > OUTBOX_MAX) q.remove(0)
      Aura.prefs.edit().putString(PREF_OUTBOX, q.toString()).apply()
    }
  }

  private val outboxLock = Any()

  private fun outbox(): JSONArray = try {
    JSONArray(Aura.prefs.getString(PREF_OUTBOX, "[]"))
  } catch (e: Exception) {
    JSONArray()
  }

  private fun flushOutbox() {
    synchronized(outboxLock) {
      val q = outbox()
      if (q.length() == 0) return
      val items = (0 until q.length()).mapNotNull { q.optJSONObject(it) }
      // la pause du bridge est appliquee AVANT (handle) : en pause totale, les phone_event de la file sont purges, pas rejoues
      val (kept, purged) = PauseLogic.splitOutbox(items, Pause.mode())
      for (e in purged) {
        Journal.event(e.optString("kind"), e, JournalLogic.PAUSED, "file purgée")
        Log.i(Aura.TAG, "phone_event ${e.optString("kind")} de la file ecarte (Aura en pause)")
      }
      val left = JSONArray()
      for (e in kept) if (!send(e)) left.put(e)
      Aura.prefs.edit().putString(PREF_OUTBOX, left.toString()).apply()
    }
  }

  private fun handle(msg: JSONObject) {
    try { LiveUpdate.onBridge(msg) } catch (e: Exception) { Log.w(Aura.TAG, "Live Update", e) }
    when (msg.optString("type")) {
      "welcome" -> {
        // AVANT online : sinon Relay.submitNow (qui attend online) pourrait envoyer un conv perime
        WatchConvs.reset(msg.optJSONArray("convs"))
        synchronized(lock) {
          status = "online"
          detail = ""
          backoffMs = 0
          badToken.reset()
        }
        deviceName = msg.str("device")
        msg.optJSONObject("settings")?.let { settings = it }
        sendCaps(force = true)
        // la pause d'abord : flushOutbox() et les evenements de rattrapage doivent deja la connaitre
        Pause.onConnected()
        Pause.onWelcome(msg)
        flushOutbox()
        Notifs.catchUp(Aura.app, msg.optJSONArray("recent"))
        WatchConvs.pushSoon()
        PhoneContext.onWelcome()
        PhoneEvents.onWelcome()
        // 01/10 soir (PROTOCOL.md §11) : types d'evenements voulus, limites en attente, rafraichissement de ce qui est affiche
        unsupportedMsgs.clear()
        Usage.onWelcome()
        Usage.maybeReportSoon()  // pas seulement au passage en arriere-plan : l'app reste surtout fermee (03/10)
        BridgeWatchdog.onOnline()
        EventsWanted.onWelcome(msg)
        LimitBanner.onWelcome(msg)
        // notifications d'appels manques / de temps encore affichees : leur etat a pu changer pendant la coupure (rappele, saisi)
        if (MissedCalls.hasShown()) send(JSONObject().put("type", "missed_list"))
        if (TimeProposals.count() > 0) send(JSONObject().put("type", "time_list"))
        Aura.emitState()
      }
      "events_wanted" -> EventsWanted.onMessage(msg)
      "missed_calls_card" -> MissedCalls.onBridge(msg)
      "time_proposals" -> TimeProposals.onProposals(msg)
      "time_result" -> TimeProposals.onResult(msg)
      "task_done" -> TaskDone.onBridge(msg)
      "limit_state" -> LimitBanner.onState(msg)
      "limit_cleared" -> LimitBanner.onCleared(msg)
      // progression des tours longs : LiveUpdate.onBridge l'a deja lue en tete de handle ; quota_state : rien a afficher ici
      "run_state", "quota_state" -> Unit
      "settings" -> {
        settings = JSONObject(msg.toString()).apply { remove("type") }
        Aura.emitState()
      }
      "action_request" -> Aura.executor.onRequest(msg)
      "action_cancel" -> msg.str("action_id")?.let { Aura.executor.onCancel(it) }
      "notify" -> {
        Notifs.showBridgeNotify(Aura.app, msg)
        Notifs.seenTs(msg.optDouble("ts", 0.0))
      }
      "pong" -> Unit
      "pause_state" -> Pause.onBridge(msg)
      "call_card" -> CallCards.onBridge(msg)
      "conv" -> {
        val conv = msg.optJSONObject("conv")
        WatchConvs.upsert(conv)
        Aura.relay.onBridge(msg)  // avant la liste : un onglet ouvert apres « busy » y est deja selectionne (WatchSink)
        // onglet demande par la montre : reconnu a son `req` (created_by = ce poste aussi pour la PWA de la WebView)
        val id = conv?.str("id")
        val req = msg.str("req")
        if (id.isNullOrEmpty() || req == null || !WatchConvs.onCreated(req, id)) WatchConvs.pushSoon()
      }
      "conv_closed" -> {
        WatchConvs.remove(msg.str("conv"))
        Aura.relay.onBridge(msg)
        WatchConvs.pushSoon()
      }
      "error" -> {
        val code = msg.optString("code")
        if (code == "bad_conv" && msg.str("conv") != null && msg.str("conv") == WatchConvs.selected()) {
          WatchConvs.select(null)  // conversation fermee ailleurs : la montre repart sur la plus recente
        }
        if (code == "device_only") {
          // jeton commun des clients au lieu du jeton appareil : le bridge refuse device_caps
          lastCaps = null
          detail = "Jeton non-appareil : actions refusées par le bridge (réappairer avec le jeton s22-natif)"
          Aura.emitState()
        } else if (code == "bad_type" && msg.optString("message").contains("phone_context")) {
          PhoneContext.unsupported = true  // bridge pas encore redemarre sur le §7.3
        } else if (code == "bad_type" && msg.optString("message").contains("pause_set")) {
          Pause.onBridgeUnsupported()  // bridge d'avant la pause : l'etat local vaut quand meme
        } else if (code == "bad_field" && msg.optString("message").startsWith("phone_event.kind")) {
          // bridge d'avant le 03/10 : seul notif_removed peut avoir un kind inconnu de lui ; les autres continuent
          PhoneEvents.removedUnsupported = true
        } else if (code == "bad_type" && msg.optString("message").contains("phone_event")) {
          PhoneEvents.unsupported = true  // bridge pas encore redemarre sur le §9.1
        } else if (code == "bad_type" && BridgeLogic.citedType(msg.optString("message"), BridgeLogic.FLUX_CLIENT_TYPES) != null) {
          // bridge d'avant le 01/10 soir : missed_list, time_list, usage_report, viewing, limit_cancel, time_confirm
          val type = BridgeLogic.citedType(msg.optString("message"), BridgeLogic.FLUX_CLIENT_TYPES)!!
          unsupportedMsgs.add(type)
          if (type == "usage_report") Usage.onUnsupported()
        } else if (code == "bad_type" && msg.optString("message").contains("device_caps")) {
          detail = "Bridge sans device_caps : actions indisponibles côté serveur"
          Aura.emitState()
        }
        Aura.relay.onError(msg)
      }
      else -> Aura.relay.onBridge(msg)
    }
  }

  fun unsupported(type: String): Boolean = type in unsupportedMsgs

  /** phone_confirm reçu du bridge (dans settings.values ou a plat), null s'il ne le gere pas encore. */
  fun phoneConfirm(): JSONObject? {
    val s = settings ?: return null
    return s.optJSONObject("phone_confirm") ?: s.optJSONObject("values")?.optJSONObject("phone_confirm")
  }

  /** device_caps : renvoye seulement si la liste change (permission, montre apparue/disparue). */
  fun sendCaps(force: Boolean = false) {
    if (!online()) return
    val caps = Perms.caps()
    val key = caps.joinToString(",") + "|" + Aura.watch.connected()
    if (!force && key == lastCaps) return
    val ok = send(JSONObject().put("type", "device_caps").put("caps", JSONArray(caps)).put("info", Aura.info()))
    if (ok) lastCaps = key
  }

  fun capsChanged() {
    Aura.io.execute { sendCaps() }
    Aura.emitState()
  }

  private fun registerNetwork() {
    if (netCallback != null) return
    val cm = Aura.app.getSystemService(ConnectivityManager::class.java) ?: return
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        Aura.timer.schedule(Runnable { kick() }, 1, TimeUnit.SECONDS)
      }
    }
    try {
      cm.registerDefaultNetworkCallback(cb)
      netCallback = cb
    } catch (e: Exception) {
      Log.w(Aura.TAG, "callback reseau non enregistre", e)
    }
  }

  private fun unregisterNetwork() {
    val cb = netCallback ?: return
    netCallback = null
    try {
      Aura.app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
    } catch (e: Exception) {
      // deja retire
    }
  }
}
