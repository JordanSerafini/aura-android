package dev.aura.mobile.device

import java.util.TreeMap

/** Lecteur de morceaux MP3 enchaines sans trou (GaplessPlayer sur Android, faux dans les tests). */
interface ChunkPlayer {
  /** Ajoute un morceau a la suite de ceux en cours ; lecture immediate si rien ne joue. */
  fun enqueue(mp3: ByteArray)
  fun stop()
  /** Rappele par le lecteur quand il n'a plus rien a jouer. */
  var onDrained: (() -> Unit)?
}

/**
 * File des `tts_audio` d'UNE reponse (PROTOCOL.md §7.1) : remet les morceaux dans l'ordre des `seq`
 * (un morceau en avance attend le precedent), les passe au lecteur des qu'ils sont contigus, et
 * signale la fin quand le dernier (`last`) a fini de jouer. Pure : testee en JVM.
 */
class TtsQueue(private val player: ChunkPlayer, private val listener: Listener) {
  interface Listener {
    fun onPlaybackStart() {}
    fun onPlaybackFinished() {}
  }

  private val pending = TreeMap<Int, ByteArray>()
  private var next = 0
  private var lastSeq = -1
  private var queued = 0  // morceaux non vides confies au lecteur et pas encore finis (approx.)
  private var started = false
  private var finished = false
  var stopped = false
    private set

  init {
    player.onDrained = { onDrained() }
  }

  @Synchronized
  fun add(seq: Int, mp3: ByteArray, last: Boolean) {
    if (stopped || finished || seq < next || pending.containsKey(seq)) return
    pending[seq] = mp3
    if (last) lastSeq = seq
    pump()
  }

  /** Aucun audio ne viendra plus (synthese en echec) : finir apres ce qui est deja confie. */
  @Synchronized
  fun abort() {
    if (stopped || finished) return
    pending.clear()
    lastSeq = next - 1
    if (queued == 0) finish()
  }

  @Synchronized
  fun stop() {
    if (stopped) return
    stopped = true
    pending.clear()
    player.stop()
  }

  val playing: Boolean @Synchronized get() = queued > 0 && !stopped

  private fun pump() {
    while (true) {
      val chunk = pending.remove(next) ?: break
      next++
      if (chunk.isEmpty()) continue
      queued++
      if (!started) {
        started = true
        listener.onPlaybackStart()
      }
      player.enqueue(chunk)
    }
    if (lastSeq >= 0 && next > lastSeq && queued == 0) finish()
  }

  @Synchronized
  private fun onDrained() {
    queued = 0
    if (stopped) return
    if (lastSeq >= 0 && next > lastSeq) finish()
  }

  private fun finish() {
    if (finished || stopped) return
    finished = true
    listener.onPlaybackFinished()
  }
}
