package dev.aura.mobile.device

import android.util.Base64
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Mode conversation vocale « Talk » (ecran natif TalkScreen) : boucle mains libres.
 *
 * listening --parole--> hearing --800 ms de silence--> sending (voice origin:"talk", speak:true)
 *   --> thinking --1er tts_audio--> speaking --fin de lecture--> listening ...
 * Le micro ne s'arrete jamais pendant la session : en sending/thinking/speaking le Vad passe en mode
 * BARGE (seuil plus haut) ; si l'utilisateur reparle, la lecture s'arrete, `tts_cancel` + `cancel` partent et
 * sa nouvelle phrase est enregistree dans la foulee (coupure). 30 s sans parole : pause (micro coupe).
 *
 * Tout passe par une file mono-thread (`q`) : pas de verrou dans la machine d'etats.
 */
class TalkSession(private val emit: (JSONObject) -> Unit) : MicRecorder.Listener, TtsQueue.Listener {
  companion object {
    private const val IDLE_PAUSE_MS = 30_000L
    private const val NO_AUDIO_MS = 15_000L
    private const val ERROR_SHOW_MS = 2_500L
  }

  private val q = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aura-talk") }
  private val vad = Vad()
  private val audio = TalkAudio(Aura.app)
  private val mic = MicRecorder(vad, this)
  private val player = GaplessPlayer(Aura.app) { audio.playbackAttributes }

  @Volatile var state = "idle"
    private set
  private var ended = false
  private var currentId: String? = null
  private var conv: String? = Aura.prefs.getString("test_conv", null)
  private var tts: TtsQueue? = null
  private var replyDone = false
  private var transcript = ""
  private var reply = ""
  private var tool: String? = null
  private var error: String? = null
  private var level = -100.0
  private var idleTimer: ScheduledFuture<*>? = null
  private var noAudioTimer: ScheduledFuture<*>? = null
  private var warmed = false

  // ─── Commandes (depuis le module, thread quelconque) ────────────────────

  fun start() = q.execute {
    if (ended) return@execute
    audio.start()
    listen()
  }

  /** Appui sur l'orbe : ecouter / mettre en pause / couper Aura. */
  fun tap() = q.execute {
    when (state) {
      "idle", "error" -> listen()
      "listening", "hearing" -> pause()
      else -> { interrupt(); listen() }
    }
  }

  fun stop() {
    q.execute {
      if (ended) return@execute
      ended = true
      interrupt()
      idleTimer?.cancel(false)
      mic.stop()
      audio.stop()
      state = "ended"
      publish()
    }
    q.shutdown()
  }

  fun snapshot(): JSONObject = JSONObject()
    .put("state", state)
    .put("transcript", transcript)
    .put("reply", reply)
    .put("tool", tool ?: JSONObject.NULL)
    .put("error", error ?: JSONObject.NULL)
    .put("level", level)
    .put("bluetooth", audio.bluetooth)
    .put("conv", conv ?: JSONObject.NULL)

  // ─── Machine d'etats (thread q) ─────────────────────────────────────────

  private fun listen() {
    error = null
    vad.mode = Vad.Mode.LISTEN
    vad.reset()
    if (!mic.active) mic.start()
    setState("listening")
    armIdle()
  }

  private fun pause() {
    idleTimer?.cancel(false)
    mic.stop()
    setState("idle")
  }

  /** Coupe la reponse en cours (lecture + generation + synthese). */
  private fun interrupt() {
    noAudioTimer?.cancel(false)
    tts?.stop()
    tts = null
    currentId?.let { Aura.relay.cancel(it) }
    currentId = null
  }

  private fun armIdle() {
    idleTimer?.cancel(false)
    idleTimer = q.schedule({ if (state == "listening") pause() }, IDLE_PAUSE_MS, TimeUnit.MILLISECONDS)
  }

  private fun setState(s: String) {
    state = s
    publish()
  }

  private fun publish() = emit(snapshot())

  private fun send(pcm: ByteArray) {
    val id = "t" + Random.nextLong().toULong().toString(16).take(11)
    val payload = JSONObject()
      .put("type", "voice")
      .put("id", id)
      .put("audio", Base64.encodeToString(Wav.wrap(pcm), Base64.NO_WRAP))
      .put("origin", "talk")
    conv?.let { payload.put("conv", it) }
    currentId = id
    replyDone = false
    transcript = ""
    reply = ""
    tool = null
    tts = TtsQueue(player, this)
    vad.mode = Vad.Mode.BARGE
    setState("sending")
    Aura.relay.submit(Relay.Req(id, payload, ReqSink(id), speak = true))
  }

  private fun backToListening() {
    noAudioTimer?.cancel(false)
    currentId = null
    tts = null
    if (!ended && state != "idle") listen()
  }

  // ─── Micro (thread aura-mic -> q) ───────────────────────────────────────

  override fun onSpeechStart() = q.execute {
    if (ended) return@execute
    idleTimer?.cancel(false)
    if (state in setOf("sending", "thinking", "speaking")) interrupt()  // coupure : l'utilisateur reparle
    vad.mode = Vad.Mode.LISTEN
    if (!warmed) {
      warmed = true
      Aura.bridge.send(JSONObject().put("type", "voice_warm"))  // Whisper se charge pendant qu'il parle
    }
    setState("hearing")
  }

  override fun onUtterance(pcm: ByteArray, reason: Vad.Event) = q.execute {
    if (ended || state != "hearing") return@execute
    warmed = false
    send(pcm)
  }

  override fun onDiscard() = q.execute {
    if (state == "hearing") { setState("listening"); armIdle() }
  }

  override fun onLevel(db: Double) {
    level = db
    if (state == "listening" || state == "hearing" || state == "speaking") emit(JSONObject().put("level", db).put("state", state))
  }

  override fun onMicError(message: String) = q.execute {
    error = message
    setState("error")
  }

  // ─── Lecture ────────────────────────────────────────────────────────────

  override fun onPlaybackStart() = q.execute {
    if (currentId != null && state != "hearing") setState("speaking")
  }

  override fun onPlaybackFinished() = q.execute {
    if (state == "speaking" || (state == "thinking" && replyDone)) backToListening()
  }

  // ─── Evenements du bridge pour une demande (thread Aura.io -> q) ─────────

  private inner class ReqSink(val id: String) : RelaySink {
    private fun mine(block: () -> Unit) = q.execute { if (!ended && currentId == id) block() }

    override fun conv(id: String) = q.execute { conv = id }

    override fun state(state: String, text: String?) = mine {
      when (state) {
        "sent", "transcribing" -> setState("sending")
        "thinking", "talking" -> if (this@TalkSession.state != "speaking") setState("thinking")
        "done" -> {
          replyDone = true
          // pas de voix 15 s apres le done (repli tts compris) : on reprend l'ecoute quand meme
          noAudioTimer = q.schedule({ if (currentId == id && this@TalkSession.state == "thinking") backToListening() },
            NO_AUDIO_MS, TimeUnit.MILLISECONDS)
        }
        "error", "offline" -> {
          error = text ?: "Erreur"
          tts?.stop()
          currentId = null
          setState("error")
          q.schedule({ if (this@TalkSession.state == "error" && !ended && currentId == null) listen() },
            ERROR_SHOW_MS, TimeUnit.MILLISECONDS)
        }
      }
    }

    override fun transcript(text: String) = mine { transcript = text; publish() }
    override fun tool(label: String) = mine { tool = label; publish() }
    override fun reply(text: String, final: Boolean) = mine { reply = text; tool = null; publish() }

    override fun audio(seq: Int, mp3: ByteArray, last: Boolean, text: String?) = mine {
      noAudioTimer?.cancel(false)
      tts?.add(seq, mp3, last)
    }

    override fun audioFailed(message: String) = mine {
      val t = tts
      if (t == null || !t.playing) backToListening() else t.abort()
    }
  }
}
