package dev.aura.mobile.device

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Detection de parole sur l'energie (trames de 20 ms), seuil adaptatif au bruit ambiant. Pure (aucune
 * API Android) : testee en JVM (src/test). Choisie plutot qu'un modele (Silero, WebRTC VAD natif) :
 * zero dependance, et le bridge refait de toute facon la transcription (Whisper).
 *
 * - LISTEN : ecoute normale, seuil = bruit + 10 dB (plancher -50 dBFS), debut apres 60 ms de voix.
 * - BARGE : pendant qu'Aura parle ou reflechit (coupure si l'utilisateur reparle) : seuil plus haut
 *   (bruit + 18 dB, plancher -38 dBFS) et BargeInLogic.MIN_VOICE_MS de voix soutenue (360 ms ; 250 jusqu'au 17/10),
 *   pour ne pas se declencher sur l'echo du haut-parleur que l'annulation d'echo n'aurait pas retire. `grace(ms)` :
 *   trames ignorees au debut d'une lecture (l'annulation d'echo converge).
 * - MUTED : « Micro coupe pendant qu'Aura parle » : niveau mesure, aucune detection, bruit de fond fige (l'echo ne
 *   doit pas le gonfler).
 * - Fin : 800 ms de silence apres au moins 200 ms de voix (sinon faux depart, ignore) ; 60 s max.
 */
class Vad(
  val frameMs: Int = 20,
  val endSilenceMs: Int = 800,
  val maxUtteranceMs: Int = 60_000,
  val minVoicedMs: Int = 200,
  val calibrationMs: Int = 200,
) {
  enum class Mode { LISTEN, BARGE, MUTED }
  enum class Event { NONE, START, END_SILENCE, END_MAX, DISCARD }

  var mode = Mode.LISTEN
  /** Estimation du bruit de fond, dBFS. */
  var noiseDb = -60.0
    private set
  var speaking = false
    private set
  var lastDb = -100.0
    private set

  private var calibFrames = 0
  private var calibSum = 0.0
  private var voicedRun = 0
  private var speechMs = 0
  private var voicedMs = 0
  private var silenceMs = 0

  fun threshold(): Double = when (mode) {
    Mode.LISTEN -> max(noiseDb + 10.0, -50.0)
    Mode.BARGE, Mode.MUTED -> max(noiseDb + 18.0, -38.0)
  }

  private fun startMs(): Int = if (mode == Mode.LISTEN) 60 else BargeInLogic.MIN_VOICE_MS

  /** Trames restantes a ignorer en BARGE (grace du debut de lecture) ; ecrit par la session, lu par le fil du micro. */
  @Volatile private var graceLeftMs = 0

  fun grace(ms: Int) {
    graceLeftMs = ms.coerceAtLeast(0)
  }

  /** Repart de zero (nouvelle ecoute) en gardant l'estimation du bruit. */
  fun reset() {
    speaking = false
    voicedRun = 0
    speechMs = 0
    voicedMs = 0
    silenceMs = 0
  }

  fun feed(db: Double): Event {
    lastDb = db
    if (calibFrames * frameMs < calibrationMs) {
      // premieres trames : le bruit ambiant de la piece, pas encore de detection
      calibFrames++
      calibSum += db
      noiseDb = calibSum / calibFrames
      return Event.NONE
    }
    if (mode == Mode.MUTED) {
      // micro coupe pendant qu'Aura parle : on mesure (l'orbe), on ne detecte rien, et une phrase commencee est oubliee
      if (speaking) reset()
      voicedRun = 0
      return Event.NONE
    }
    if (mode == Mode.BARGE && !speaking && graceLeftMs > 0) {
      graceLeftMs -= frameMs
      voicedRun = 0
      return Event.NONE
    }
    val th = threshold()
    if (!speaking) {
      if (db > th) {
        voicedRun += frameMs
      } else {
        voicedRun = max(0, voicedRun - frameMs)
        // bruit : descend vite (porte qui se ferme), remonte lentement (ventilateur qui demarre)
        noiseDb += if (db < noiseDb) (db - noiseDb) * 0.2 else (db - noiseDb) * 0.02
        noiseDb = noiseDb.coerceIn(-90.0, -20.0)
      }
      if (voicedRun >= startMs()) {
        speaking = true
        speechMs = voicedRun
        voicedMs = voicedRun
        silenceMs = 0
        voicedRun = 0
        return Event.START
      }
      return Event.NONE
    }
    speechMs += frameMs
    // hysteresis : une voix qui faiblit en fin de phrase compte encore
    if (db > th - 3.0) {
      voicedMs += frameMs
      silenceMs = 0
    } else {
      silenceMs += frameMs
    }
    if (speechMs >= maxUtteranceMs) {
      reset()
      return Event.END_MAX
    }
    if (silenceMs >= endSilenceMs) {
      val real = voicedMs >= minVoicedMs
      reset()
      return if (real) Event.END_SILENCE else Event.DISCARD
    }
    return Event.NONE
  }

  companion object {
    /** Niveau RMS d'une trame PCM 16 bits, en dBFS (-100 pour le silence numerique). */
    fun dbfs(samples: ShortArray, n: Int = samples.size): Double {
      if (n <= 0) return -100.0
      var sum = 0.0
      for (i in 0 until n) {
        val v = samples[i].toDouble()
        sum += v * v
      }
      val rms = sqrt(sum / n)
      if (rms < 1.0) return -100.0
      return 20.0 * log10(rms / 32768.0)
    }
  }
}
