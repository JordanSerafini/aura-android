package dev.aura.mobile.device

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Routage audio d'une session vocale (Talk, assistant) : focus audio (la musique se met en pause),
 * et si un casque Bluetooth est la, mode communication + micro/ecouteur du casque.
 */
class TalkAudio(private val ctx: Context) {
  private val am = ctx.getSystemService(AudioManager::class.java)
  private var focus: AudioFocusRequest? = null
  var bluetooth = false
    private set

  /** Usage des lectures : communication sur le casque BT (SCO), assistant sur le haut-parleur. */
  val playbackAttributes: AudioAttributes
    get() = AudioAttributes.Builder()
      .setUsage(if (bluetooth) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
      .build()

  fun start() {
    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
      .setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build())
      .setOnAudioFocusChangeListener { }
      .build()
    try {
      am.requestAudioFocus(req)
      focus = req
    } catch (e: Exception) {
      Log.w(Aura.TAG, "focus audio refuse", e)
    }
    bluetooth = false
    if (Build.VERSION.SDK_INT >= 31) {
      val bt = am.availableCommunicationDevices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
      }
      if (bt != null) {
        try {
          am.mode = AudioManager.MODE_IN_COMMUNICATION
          bluetooth = am.setCommunicationDevice(bt)
          if (!bluetooth) am.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
          Log.w(Aura.TAG, "casque Bluetooth non selectionne", e)
          am.mode = AudioManager.MODE_NORMAL
        }
      }
    }
  }

  fun stop() {
    if (bluetooth && Build.VERSION.SDK_INT >= 31) {
      try { am.clearCommunicationDevice() } catch (e: Exception) { /* deja libere */ }
      am.mode = AudioManager.MODE_NORMAL
    }
    bluetooth = false
    focus?.let { try { am.abandonAudioFocusRequest(it) } catch (e: Exception) { /* rien */ } }
    focus = null
  }
}

/**
 * Micro continu, 16 kHz mono PCM 16 bits, trames de 20 ms passees au Vad. Garde 500 ms avant le debut
 * de parole (la premiere syllabe n'est pas coupee). Source VOICE_COMMUNICATION + annulation d'echo :
 * Aura parle pendant que le micro ecoute (coupure si l'utilisateur reparle).
 */
class MicRecorder(private val vad: Vad, private val listener: Listener) {
  interface Listener {
    fun onSpeechStart()
    fun onUtterance(pcm: ByteArray, reason: Vad.Event)
    fun onDiscard()
    fun onLevel(db: Double)
    fun onMicError(message: String)
  }

  companion object {
    const val RATE = 16_000
    const val FRAME = 320  // 20 ms
    private const val PREROLL_FRAMES = 25
  }

  @Volatile private var running = false
  private var thread: Thread? = null

  val active: Boolean get() = running

  @SuppressLint("MissingPermission")
  fun start() {
    if (running) return
    running = true
    thread = Thread({ loop() }, "aura-mic").apply { start() }
  }

  fun stop() {
    running = false
    thread?.join(1000)
    thread = null
  }

  @SuppressLint("MissingPermission")
  private fun loop() {
    val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = try {
      AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE / 2))
    } catch (e: Exception) {
      running = false
      listener.onMicError("Micro indisponible : ${e.message}")
      return
    }
    if (rec.state != AudioRecord.STATE_INITIALIZED) {
      rec.release()
      running = false
      listener.onMicError("Micro indisponible (permission refusée ou déjà utilisé)")
      return
    }
    val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId) else null
    val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId) else null
    try { aec?.enabled = true; ns?.enabled = true } catch (e: Exception) { /* effet refuse */ }
    val frame = ShortArray(FRAME)
    val preroll = ArrayDeque<ShortArray>()
    val utterance = ByteArrayOutputStream()
    var lastLevel = 0L
    try {
      rec.startRecording()
      while (running) {
        var got = 0
        while (got < FRAME && running) {
          val n = rec.read(frame, got, FRAME - got)
          if (n < 0) throw IllegalStateException("lecture micro : $n")
          got += n
        }
        if (!running) break
        val db = Vad.dbfs(frame, got)
        val now = System.currentTimeMillis()
        if (now - lastLevel >= 100) {
          lastLevel = now
          listener.onLevel(db)
        }
        val wasSpeaking = vad.speaking
        val ev = vad.feed(db)
        if (ev == Vad.Event.START) {
          utterance.reset()
          preroll.forEach { writePcm(utterance, it) }
          writePcm(utterance, frame)
          listener.onSpeechStart()
        } else if (wasSpeaking) {
          writePcm(utterance, frame)
        }
        when (ev) {
          Vad.Event.END_SILENCE, Vad.Event.END_MAX -> {
            val pcm = utterance.toByteArray()
            utterance.reset()
            listener.onUtterance(pcm, ev)
          }
          Vad.Event.DISCARD -> {
            utterance.reset()
            listener.onDiscard()
          }
          else -> Unit
        }
        preroll.addLast(frame.copyOf())
        while (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
      }
    } catch (e: Exception) {
      if (running) listener.onMicError("Micro coupé : ${e.message}")
    } finally {
      running = false
      try { rec.stop() } catch (e: Exception) { /* deja arrete */ }
      rec.release()
      aec?.release()
      ns?.release()
    }
  }

  private fun writePcm(out: ByteArrayOutputStream, s: ShortArray) {
    val b = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    s.forEach { b.putShort(it) }
    out.write(b.array())
  }
}

/** En-tete WAV (PCM 16 bits mono) : format accepte par le bridge (protocol.decode_audio). */
object Wav {
  fun wrap(pcm: ByteArray, rate: Int = MicRecorder.RATE): ByteArray {
    val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
    b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    b.put("data".toByteArray()).putInt(pcm.size).put(pcm)
    return b.array()
  }
}

/**
 * Lecture sans trou des morceaux MP3 : chaque morceau est prepare des son arrivee et chaine au
 * precedent par setNextMediaPlayer (transition geree par le framework, pas de silence entre phrases).
 */
class GaplessPlayer(private val ctx: Context, private val attrs: () -> AudioAttributes) : ChunkPlayer {
  private val main = Handler(Looper.getMainLooper())
  private val queue = ArrayDeque<Pair<MediaPlayer, File>>()
  private var seq = 0
  override var onDrained: (() -> Unit)? = null

  override fun enqueue(mp3: ByteArray) {
    val dir = File(ctx.cacheDir, "talk").apply { mkdirs() }
    val f = File(dir, "tts-${System.nanoTime()}-${seq++}.mp3")
    f.writeBytes(mp3)
    val mp = MediaPlayer()
    try {
      mp.setAudioAttributes(attrs())
      mp.setDataSource(f.absolutePath)
      mp.prepare()
    } catch (e: Exception) {
      Log.w(Aura.TAG, "morceau audio illisible", e)
      mp.release()
      f.delete()
      val empty = synchronized(this) { queue.isEmpty() }
      if (empty) main.post { onDrained?.invoke() }
      return
    }
    mp.setOnCompletionListener { done(it) }
    mp.setOnErrorListener { p, _, _ -> done(p); true }
    synchronized(this) {
      queue.addLast(mp to f)
      when (queue.size) {
        1 -> mp.start()
        2 -> try { queue[0].first.setNextMediaPlayer(mp) } catch (e: Exception) { /* le 1er vient de finir */ }
      }
    }
  }

  private fun done(mp: MediaPlayer) {
    var drained = false
    synchronized(this) {
      val head = queue.firstOrNull() ?: return
      if (head.first !== mp) return
      queue.removeFirst()
      mp.release()
      head.second.delete()
      val cur = queue.firstOrNull()
      if (cur == null) drained = true
      else {
        // setNextMediaPlayer a deja demarre `cur` ; sinon (chaine posee trop tard) on le lance
        try { if (!cur.first.isPlaying) cur.first.start() } catch (e: Exception) { /* etat invalide */ }
        if (queue.size >= 2) try { cur.first.setNextMediaPlayer(queue[1].first) } catch (e: Exception) { /* rien */ }
      }
    }
    // hors du verrou : TtsQueue.onDrained prend le sien (sinon interblocage avec enqueue)
    if (drained) onDrained?.invoke()
  }

  override fun stop() {
    synchronized(this) {
      queue.forEach { (mp, f) ->
        try { mp.stop() } catch (e: Exception) { /* pas demarre */ }
        mp.release()
        f.delete()
      }
      queue.clear()
    }
  }
}
