package dev.aura.mobile.device

import org.json.JSONObject
import java.util.Base64
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Destinataire des evenements d'une demande envoyee au bridge : montre (WatchRelay), mode Talk,
 * assistant, partage. Tout est rappele hors du thread principal.
 */
interface RelaySink {
  /** sent | transcribing | thinking | talking | done | error | offline */
  fun state(state: String, text: String?) {}
  /** Ce que le bridge a compris (echo du message de l'utilisateur, sans le prefixe 🎙️). */
  fun transcript(text: String) {}
  fun tool(label: String) {}
  /** Texte CUMULE de la reponse ; final = reponse complete nettoyee (done). */
  fun reply(text: String, final: Boolean) {}
  /** Morceau MP3 (seq croissant ; audio vide possible, surtout pour le dernier). */
  fun audio(seq: Int, mp3: ByteArray, last: Boolean, text: String?) {}
  fun audioFailed(message: String) {}
  /** Au done, sans `speak` : demander la voix apres coup (montre, selon voice_mode) ? */
  fun ttsAfterDone(): Boolean = false
  /** Conversation apprise (echo, 1er delta, ou conv_new apres un « busy »). */
  fun conv(id: String) {}
}

/** Ce dont le relais a besoin du monde exterieur : remplace par un faux dans les tests JVM. */
interface RelayIo {
  fun online(): Boolean
  fun rejected(): Boolean
  fun send(obj: JSONObject): Boolean
  fun deviceName(): String?
  fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
  fun async(task: () -> Unit)
  fun sleep(ms: Long)
  fun now(): Long
  fun submitted(id: String) {}
}

/**
 * Suivi des demandes envoyees au bridge par la connexion native (PROTOCOL.md §4, §5, §7.1).
 * Les evenements du bridge portent `id` (delta, done, error, tts_*) ou seulement `conv` (state) :
 * la conversation d'une demande est apprise par l'echo `message` ou le premier delta.
 *
 * Voix en flux (§7.1) : une demande `speak` recoit ses `tts_audio` au fil de l'eau ; si AUCUN n'est
 * arrive 3 s apres le done (bridge ancien qui ignore `speak`, synthese en panne), repli sur `tts`.
 */
class Relay(private val io: RelayIo) {
  companion object {
    const val ONLINE_WAIT_MS = 12_000L
    const val TTS_FALLBACK_MS = 3_000L
    private const val REQ_MAX_AGE_MS = 30 * 60_000L

    /** Bridge d'avant le 27/09 : seul origin "watch" est accepte. Talk a la meme consigne (reponse courte). */
    fun downgradeOrigin(origin: String?): String? = if (origin == "talk") "watch" else null
  }

  /** newConv : la demande part dans une conversation neuve (partage), creee par conv_new juste avant. */
  class Req(val id: String, val payload: JSONObject, val sink: RelaySink, val speak: Boolean = false,
            val newConv: Boolean = false) {
    val created = System.currentTimeMillis()
    @Volatile var conv: String? = payload.optString("conv").ifEmpty { null }
    val text = StringBuilder()
    @Volatile var state = ""
    @Volatile var busyRetried = false
    @Volatile var originRetried = false
    @Volatile var done = false
    @Volatile var audioSeen = false
    @Volatile var fallbackSent = false
    @Volatile var finalText = ""
    @Volatile var cancelFallback: (() -> Unit)? = null
    /** id de la demande `tts` de repli (distinct : les tts_audio tardifs du flux sont alors ignores, §7.5). */
    @Volatile var fallbackId: String? = null
  }

  private val reqs = ConcurrentHashMap<String, Req>()
  private val tts = ConcurrentHashMap<String, Req>()
  @Volatile private var awaitingConv: Req? = null

  fun active(id: String): Boolean = reqs.containsKey(id) || tts.containsKey(id)

  /** Envoi asynchrone : attend jusqu'a 12 s le welcome si la WebSocket se (re)connecte. */
  fun submit(req: Req) = io.async { submitNow(req) }

  fun submitNow(req: Req) {
    prune()
    val deadline = io.now() + ONLINE_WAIT_MS
    while (!io.online() && !io.rejected() && io.now() < deadline) io.sleep(250)
    if (!io.online()) {
      req.sink.state("offline", "Serveur injoignable")
      return
    }
    if (req.speak) {
      req.payload.put("speak", true)
      tts[req.id] = req
    }
    reqs[req.id] = req
    io.submitted(req.id)
    if (req.newConv) {
      if (convNew(req)) return
      reqs.remove(req.id)
      tts.remove(req.id)
      req.sink.state("offline", "Serveur injoignable")
      return
    }
    if (!io.send(req.payload)) {
      reqs.remove(req.id)
      tts.remove(req.id)
      req.sink.state("offline", "Serveur injoignable")
      return
    }
    setState(req, "sent")
  }

  /** Abandon cote client (barge-in, raccrocher) : le bridge arrete de generer et de synthetiser. */
  fun cancel(id: String, stopAnswer: Boolean = true) {
    val req = reqs.remove(id)
    // apres le repli, la voix est rangee sous l'id du repli (<id>.tts)
    var t = tts.remove(id) ?: tts.remove("$id.tts")
    val fid = (req ?: t)?.fallbackId
    if (fid != null) t = tts.remove(fid) ?: t
    (req ?: t)?.cancelFallback?.invoke()
    if (t != null || req?.speak == true) io.send(JSONObject().put("type", "tts_cancel").put("id", fid ?: id))
    if (stopAnswer && req != null) io.send(JSONObject().put("type", "cancel").put("id", id))
  }

  fun onBridge(msg: JSONObject) {
    when (msg.optString("type")) {
      "delta" -> {
        val req = msg.str("id")?.let { reqs[it] } ?: return
        learnConv(req, msg.str("conv"))
        val tool = msg.str("tool")
        val text = msg.str("text").orEmpty()
        if (tool == "transcription") setState(req, "transcribing")
        else if (tool != null) {
          req.sink.tool(tool)
          if (req.state != "talking") setState(req, "thinking")
        }
        if (text.isNotEmpty()) {
          val all = synchronized(req) { req.text.append(text).toString() }
          setState(req, "talking")
          req.sink.reply(all, false)
        }
      }
      "message" -> {
        // echo du message de l'utilisateur (meme id) : conversation avant le premier delta + transcription
        val m = msg.optJSONObject("message") ?: return
        val req = m.str("id")?.let { reqs[it] } ?: return
        learnConv(req, msg.str("conv"))
        if (m.str("role") == "me") {
          val said = m.str("text").orEmpty().removePrefix("🎙️").trim()
          if (said.isNotEmpty()) req.sink.transcript(said)
          if (req.state == "sent" || req.state == "transcribing") setState(req, "thinking")
        }
      }
      "state" -> {
        val conv = msg.str("conv") ?: return
        if (msg.optString("value") != "thinking") return
        reqs.values.filter { it.conv == conv && (it.state == "sent" || it.state == "transcribing") }
          .forEach { setState(it, "thinking") }
      }
      "done" -> {
        val req = msg.str("id")?.let { reqs.remove(it) } ?: return
        learnConv(req, msg.str("conv"))
        req.done = true
        val final = msg.str("text").orEmpty().ifEmpty { synchronized(req) { req.text.toString() } }
        val error = msg.str("error")
        if (final.isBlank()) {
          tts.remove(req.id)
          setState(req, "error", if (error == "annule") "Annulé" else error ?: "Pas de réponse")
          return
        }
        req.finalText = final
        req.sink.reply(final, true)
        setState(req, "done")
        when {
          req.speak -> if (!req.audioSeen) armFallback(req)
          req.sink.ttsAfterDone() -> {
            tts[req.id] = req
            if (!io.send(JSONObject().put("type", "tts").put("id", req.id).put("text", final))) tts.remove(req.id)
          }
        }
      }
      "tts_audio" -> {
        val id = msg.str("id") ?: return
        val req = tts[id] ?: return
        req.audioSeen = true
        req.cancelFallback?.invoke()
        val b64 = msg.str("audio").orEmpty()
        val bytes = if (b64.isEmpty()) ByteArray(0) else try {
          Base64.getMimeDecoder().decode(b64)
        } catch (e: IllegalArgumentException) {
          ByteArray(0)
        }
        val last = msg.optBoolean("last")
        if (last) tts.remove(id)
        req.sink.audio(msg.optInt("seq", 0), bytes, last, msg.str("text"))
      }
      "tts_error" -> {
        val id = msg.str("id") ?: return
        val req = tts[id] ?: return
        // voix en flux en panne avant tout audio : la reponse texte continue, le repli `tts` du done
        // retentera ; sinon (repli deja tente, ou audio partiel) c'est fini pour la voix
        if (req.speak && !req.done && !req.audioSeen) return
        tts.remove(id)
        req.cancelFallback?.invoke()
        req.sink.audioFailed(msg.str("message") ?: "Synthèse vocale indisponible")
      }
      "conv" -> {
        // conv_new demande apres un « busy » : reconnue a son `req` ; bridge d'avant le 28/09 (sans req) :
        // created_by = notre poste, ambigu (la PWA de la WebView et la montre partagent ce poste)
        val req = awaitingConv ?: return
        val tag = msg.str("req")
        if (tag != null) {
          if (tag != convTag(req)) return
        } else if (msg.str("created_by") != io.deviceName()) return
        val conv = msg.optJSONObject("conv")?.str("id") ?: return
        awaitingConv = null
        if (reqs[req.id] !== req) return
        req.payload.put("conv", conv)
        learnConv(req, conv)
        if (!io.send(req.payload)) {
          reqs.remove(req.id)
          tts.remove(req.id)
          req.sink.state("offline", "Serveur injoignable")
        } else if (req.state.isEmpty()) {
          setState(req, "sent")
        }
      }
    }
  }

  fun onError(msg: JSONObject) {
    val req = msg.str("id")?.let { reqs[it] } ?: return
    val code = msg.optString("code")
    if (code == "busy" && !req.busyRetried) {
      // la conversation repond deja (ailleurs) : pas d'attente, onglet neuf
      req.busyRetried = true
      if (convNew(req)) return
    }
    val origin = req.payload.str("origin")
    if (code == "bad_field" && origin != null && origin != "watch" && !req.originRetried &&
      msg.optString("message").contains("origin")) {
      // bridge pas encore redemarre sur le §7.2 : on renvoie avec l'origine qu'il connait
      req.originRetried = true
      val down = downgradeOrigin(origin)
      if (down == null) req.payload.remove("origin") else req.payload.put("origin", down)
      if (io.send(req.payload)) return
    }
    reqs.remove(req.id)
    tts.remove(req.id)
    setState(req, "error", msg.str("message") ?: "Erreur du bridge")
  }

  fun bridgeDown() {
    val lost = (reqs.values + tts.values).toSet()
    reqs.clear()
    tts.clear()
    awaitingConv = null
    lost.forEach {
      it.cancelFallback?.invoke()
      if (!it.done) it.sink.state("offline", "Connexion au serveur perdue")
      else it.sink.audioFailed("Connexion au serveur perdue")
    }
  }

  /** `req` du conv_new : prefixe distinct des req de la montre (WatchConvs), 64 car. comme le bridge. */
  private fun convTag(req: Req) = ("relay:" + req.id).take(64)

  private fun convNew(req: Req): Boolean {
    awaitingConv = req
    return io.send(JSONObject().put("type", "conv_new").put("req", convTag(req)))
  }

  private fun armFallback(req: Req) {
    req.cancelFallback = io.schedule(TTS_FALLBACK_MS) {
      if (req.audioSeen || req.fallbackSent || tts[req.id] !== req) return@schedule
      req.fallbackSent = true
      // id distinct : un tts_audio tardif du flux (meme id que la requete) ne se melange pas a la voix du repli
      val fid = req.id + ".tts"
      req.fallbackId = fid
      tts.remove(req.id)
      tts[fid] = req
      if (!io.send(JSONObject().put("type", "tts").put("id", fid).put("text", req.finalText))) {
        tts.remove(fid)
        req.sink.audioFailed("Serveur injoignable")
      }
    }
  }

  private fun learnConv(req: Req, conv: String?) {
    if (conv.isNullOrEmpty() || req.conv == conv) return
    req.conv = conv
    req.sink.conv(conv)
  }

  private fun prune() {
    val limit = io.now() - REQ_MAX_AGE_MS
    reqs.values.filter { it.created < limit }.forEach { reqs.remove(it.id) }
    tts.values.filter { it.created < limit }.forEach { tts.remove(it.id) }
  }

  private fun setState(req: Req, state: String, text: String? = null) {
    if (req.state == state && text == null) return
    req.state = state
    req.sink.state(state, text)
  }
}

/** Morceaux MP3 d'une reponse remis dans l'ordre des seq (la montre veut un seul fichier). */
class ChunkCollector {
  private val parts = TreeMap<Int, ByteArray>()
  @Synchronized fun add(seq: Int, bytes: ByteArray) { parts[seq] = bytes }
  @Synchronized fun bytes(): ByteArray = parts.values.fold(ByteArray(0)) { acc, b -> acc + b }
}
