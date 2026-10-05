package dev.aura.mobile.device

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Wearable Data Layer cote telephone (docs/PROTOCOL.md §3). */
class WatchLink {
  companion object {
    const val CAP_WATCH = "aura_watch"
    const val CMD_TIMEOUT_S = 35L
  }

  private val ctx get() = Aura.app
  private val capClient by lazy { Wearable.getCapabilityClient(ctx) }
  private val msgClient by lazy { Wearable.getMessageClient(ctx) }
  private val chanClient by lazy { Wearable.getChannelClient(ctx) }
  private val dataClient by lazy { Wearable.getDataClient(ctx) }

  @Volatile private var nodes: List<Node> = emptyList()
  @Volatile private var listening = false
  @Volatile var lastError: String? = null
    private set
  private val cmds = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
  private val pongs = ConcurrentHashMap.newKeySet<CompletableFuture<Long>>()

  private val capListener = CapabilityClient.OnCapabilityChangedListener { info -> update(info) }

  fun connected() = nodes.isNotEmpty()

  fun stateJson(): JSONObject = JSONObject()
    .put("connected", connected())
    .put("name", nodes.firstOrNull()?.displayName ?: Aura.prefs.getString("watch_name", null) ?: JSONObject.NULL)
    .put("lastSeen", Aura.prefs.getLong("watch_last_seen", 0L))
    .put("error", lastError ?: JSONObject.NULL)

  fun startListening() {
    if (listening) return
    listening = true
    try {
      capClient.addListener(capListener, CAP_WATCH)
    } catch (e: Exception) {
      lastError = e.message
    }
    Aura.pool.execute { refresh() }
  }

  fun stopListening() {
    if (!listening) return
    listening = false
    try { capClient.removeListener(capListener, CAP_WATCH) } catch (e: Exception) { /* deja retire */ }
  }

  /** Relit les montres joignables qui declarent aura_watch. Bloquant : hors thread principal. */
  fun refresh(): Boolean {
    try {
      val info = Tasks.await(capClient.getCapability(CAP_WATCH, CapabilityClient.FILTER_REACHABLE), 10, TimeUnit.SECONDS)
      lastError = null
      update(info)
    } catch (e: Exception) {
      lastError = "Data Layer : ${e.cause?.message ?: e.message}"
      Log.w(Aura.TAG, "capability aura_watch illisible", e)
      setNodes(emptyList())
    }
    return connected()
  }

  fun update(info: CapabilityInfo) = setNodes(info.nodes.toList())

  private fun setNodes(list: List<Node>) {
    val was = connected()
    nodes = list.sortedByDescending { it.isNearby }
    if (list.isNotEmpty()) {
      Aura.prefs.edit().putString("watch_name", list.first().displayName).apply()
      seen()
    }
    if (was != connected()) {
      Aura.bridge.capsChanged()
      if (connected()) {
        pushSettings()
        WatchStatus.push(force = true)
      }
    }
    Aura.emitState()
  }

  fun seen() {
    Aura.prefs.edit().putLong("watch_last_seen", System.currentTimeMillis()).apply()
  }

  fun send(nodeId: String, path: String, json: JSONObject): Boolean = try {
    Tasks.await(msgClient.sendMessage(nodeId, path, json.toString().toByteArray(Charsets.UTF_8)), 10, TimeUnit.SECONDS)
    true
  } catch (e: Exception) {
    Log.w(Aura.TAG, "message $path non envoye", e)
    false
  }

  /** Envoi asynchrone a toutes les montres aura_watch joignables. */
  fun sendToWatches(path: String, json: JSONObject) {
    val targets = nodes
    if (targets.isEmpty()) return
    Aura.wearQ.execute { targets.forEach { send(it.id, path, json) } }
  }

  /** Fichier par ChannelClient (MP3 de la voix de synthèse vers /aura/audio/<id>). Bloquant. */
  fun sendFile(nodeId: String, path: String, bytes: ByteArray): Boolean {
    return try {
      val channel = Tasks.await(chanClient.openChannel(nodeId, path), 15, TimeUnit.SECONDS)
      val out = Tasks.await(chanClient.getOutputStream(channel), 15, TimeUnit.SECONDS)
      out.use { it.write(bytes); it.flush() }
      true
    } catch (e: Exception) {
      Log.w(Aura.TAG, "fichier $path non envoye", e)
      false
    }
  }

  fun readChannel(channel: com.google.android.gms.wearable.ChannelClient.Channel): ByteArray {
    try {
      val input = Tasks.await(chanClient.getInputStream(channel), 15, TimeUnit.SECONDS)
      return input.use { it.readBytes() }
    } finally {
      // la montre ferme aussi apres onOutputClosed (PROTOCOL.md §6) : fermer deux fois est sans effet
      try { Tasks.await(chanClient.close(channel), 5, TimeUnit.SECONDS) } catch (e: Exception) { /* deja ferme */ }
    }
  }

  /** Commande montre (/aura/cmd) et attente de /aura/cmd_result, 35 s. */
  fun cmd(cmd: String, params: JSONObject): Any? {
    val node = nodes.firstOrNull() ?: throw ActionError("unsupported", "Montre non connectée")
    val reqId = UUID.randomUUID().toString()
    val future = CompletableFuture<JSONObject>()
    cmds[reqId] = future
    try {
      val payload = JSONObject().put("req_id", reqId).put("cmd", cmd).put("params", params)
      if (!send(node.id, "/aura/cmd", payload)) throw ActionError("watch_unreachable", "Montre injoignable")
      val res = try {
        future.get(CMD_TIMEOUT_S, TimeUnit.SECONDS)
      } catch (e: TimeoutException) {
        throw ActionError("timeout", "La montre n'a pas répondu en $CMD_TIMEOUT_S s")
      }
      if (!res.optBoolean("ok")) {
        val err = res.str("error") ?: "échec sur la montre"
        throw ActionError(err, "Montre : $err")
      }
      return if (res.has("result")) res.get("result") else null
    } finally {
      cmds.remove(reqId)
    }
  }

  fun onCmdResult(json: JSONObject) {
    json.str("req_id")?.let { cmds[it]?.complete(json) }
  }

  /** Ping applicatif : rend l'aller-retour en ms. */
  fun ping(): Long {
    if (nodes.isEmpty()) refresh()
    val node = nodes.firstOrNull() ?: throw ActionError("unsupported", "Aucune montre Aura joignable")
    val future = CompletableFuture<Long>()
    pongs += future
    val t0 = System.currentTimeMillis()
    try {
      if (!send(node.id, "/aura/ping", JSONObject())) throw ActionError("watch_unreachable", "Message non remis à la montre")
      val t1 = try { future.get(10, TimeUnit.SECONDS) } catch (e: TimeoutException) {
        throw ActionError("timeout", "Pas de /aura/pong en 10 s (app montre fermée ?)")
      }
      return t1 - t0
    } finally {
      pongs -= future
    }
  }

  fun onPong() {
    val now = System.currentTimeMillis()
    pongs.forEach { it.complete(now) }
  }

  /** DataItem /aura/settings : JSON UTF-8 brut dans les donnees de l'item (docs/PROTOCOL.md §3). */
  fun pushSettings() {
    Aura.pool.execute {
      try {
        val json = Aura.watchSettings().put("updated", System.currentTimeMillis())
        val req = PutDataRequest.create("/aura/settings").setData(json.toString().toByteArray(Charsets.UTF_8)).setUrgent()
        Tasks.await(dataClient.putDataItem(req), 15, TimeUnit.SECONDS)
      } catch (e: Exception) {
        Log.w(Aura.TAG, "reglages montre non publies", e)
      }
    }
  }
}
