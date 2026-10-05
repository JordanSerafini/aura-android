package dev.aura.mobile.device

import android.util.Base64
import org.json.JSONObject

/**
 * Relais montre <-> bridge (docs/PROTOCOL.md §4). Le suivi des demandes est dans Relay (partage
 * avec Talk, l'assistant et le partage) ; ici, seulement la traduction vers le Data Layer.
 */
object WatchRelay {
  private const val REPLY_MAX = 2000
  private const val REPLY_THROTTLE_MS = 700L

  /** Evenements d'une demande de la montre -> /aura/state, /aura/reply, /aura/audio. */
  /** explicit : la demande part dans l'onglet choisi sur la montre (sinon la plus recente du bridge). */
  private class WatchSink(val node: String, val id: String, val explicit: Boolean) : RelaySink {
    @Volatile private var lastState = ""
    @Volatile private var lastReplyAt = 0L
    private val audio = ChunkCollector()

    override fun state(state: String, text: String?) {
      // la montre ne connait que sent|transcribing|thinking|done|error|offline (§3)
      val s = if (state == "talking") "thinking" else state
      if (s == lastState && text == null) return
      lastState = s
      stateTo(node, id, s, text)
    }

    override fun reply(text: String, final: Boolean) {
      val now = System.currentTimeMillis()
      if (!final && now - lastReplyAt < REPLY_THROTTLE_MS) return
      lastReplyAt = now
      val shown = if (text.length > REPLY_MAX) text.take(REPLY_MAX - 1) + "…" else text
      val json = JSONObject().put("id", id).put("text", shown).put("final", final)
      Aura.wearQ.execute { Aura.watch.send(node, "/aura/reply", json) }
    }

    override fun ttsAfterDone(): Boolean = Aura.voiceWanted()

    // onglet neuf ouvert par le relais (conversation occupee, « busy ») : la montre le suit si elle avait un choix
    override fun conv(id: String) {
      if (!explicit || id == WatchConvs.selected()) return
      WatchConvs.select(id)
      WatchConvs.pushSoon()
    }

    override fun audio(seq: Int, mp3: ByteArray, last: Boolean, text: String?) {
      if (mp3.isNotEmpty()) audio.add(seq, mp3)
      if (!last) return
      val bytes = audio.bytes()
      if (bytes.isNotEmpty()) Aura.pool.execute { Aura.watch.sendFile(node, "/aura/audio/$id", bytes) }
    }
  }

  fun onVoice(node: String, id: String, audio: ByteArray) {
    val msg = JSONObject()
      .put("type", "voice")
      .put("id", id)
      .put("audio", Base64.encodeToString(audio, Base64.NO_WRAP))
      .put("origin", "watch")
    submit(node, id, msg)
  }

  fun onChat(node: String, json: JSONObject) {
    val id = json.str("id") ?: return
    val text = json.str("text")?.trim().orEmpty()
    if (text.isEmpty()) return
    submit(node, id, JSONObject().put("type", "chat").put("id", id).put("text", text).put("origin", "watch"))
  }

  /** Onglet neuf en cours de creation : retenu jusqu'a son id (15 s) ; sinon l'onglet choisi, ou la plus recente. */
  private fun submit(node: String, id: String, msg: JSONObject) {
    WatchConvs.heard(node)
    val send = { conv: String? ->
      if (conv != null) msg.put("conv", conv) else msg.remove("conv")
      Aura.relay.submit(Relay.Req(id, msg, WatchSink(node, id, conv != null)))
    }
    if (!WatchConvs.holdIfWaiting(send)) send(WatchConvs.selected())
  }

  fun stateTo(node: String, id: String, state: String, text: String? = null) {
    val json = JSONObject().put("id", id).put("state", state)
    if (text != null) json.put("text", text)
    Aura.wearQ.execute { Aura.watch.send(node, "/aura/state", json) }
  }
}

/** Le Relay branche sur la WebSocket native et les executeurs du processus. */
object BridgeIo : RelayIo {
  override fun online() = Aura.bridge.online()
  override fun rejected() = Aura.bridge.status == "rejected"
  override fun send(obj: JSONObject) = Aura.bridge.send(obj)
  override fun deviceName() = Aura.bridge.deviceName
  override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
    val f = Aura.timer.schedule(Runnable { Aura.io.execute(task) }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    return { f.cancel(false) }
  }
  override fun async(task: () -> Unit) = Aura.pool.execute(task)
  override fun sleep(ms: Long) = Thread.sleep(ms)
  override fun now() = System.currentTimeMillis()
  override fun submitted(id: String) = LiveUpdate.track(id)
}
