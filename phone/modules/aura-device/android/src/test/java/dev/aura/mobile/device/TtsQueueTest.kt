package dev.aura.mobile.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsQueueTest {
  /** Faux lecteur : garde l'ordre de ce qu'on lui confie ; finish() simule la fin de tout ce qui joue. */
  class FakePlayer : ChunkPlayer {
    val played = mutableListOf<String>()
    var stopped = false
    override var onDrained: (() -> Unit)? = null
    override fun enqueue(mp3: ByteArray) { played += String(mp3) }
    override fun stop() { stopped = true }
    fun drain() = onDrained?.invoke()
  }

  class L : TtsQueue.Listener {
    var starts = 0
    var finished = 0
    override fun onPlaybackStart() { starts++ }
    override fun onPlaybackFinished() { finished++ }
  }

  private fun b(s: String) = s.toByteArray()

  @Test fun morceaux_dans_l_ordre_des_seq_meme_arrives_en_desordre() {
    val p = FakePlayer(); val l = L(); val q = TtsQueue(p, l)
    q.add(1, b("B"), false)
    assertTrue("seq 1 attend seq 0", p.played.isEmpty())
    q.add(0, b("A"), false)
    q.add(3, b("D"), true)
    q.add(2, b("C"), false)
    assertEquals(listOf("A", "B", "C", "D"), p.played)
    assertEquals(1, l.starts)
    assertEquals(0, l.finished)
    p.drain()
    assertEquals(1, l.finished)
  }

  @Test fun dernier_morceau_vide_termine_apres_la_lecture() {
    val p = FakePlayer(); val l = L(); val q = TtsQueue(p, l)
    q.add(0, b("A"), false)
    q.add(1, ByteArray(0), true)
    assertEquals(0, l.finished)
    p.drain()
    assertEquals(1, l.finished)
  }

  @Test fun texte_vide_un_seul_morceau_vide_fin_immediate() {
    val p = FakePlayer(); val l = L(); val q = TtsQueue(p, l)
    q.add(0, ByteArray(0), true)
    assertEquals(0, l.starts)
    assertEquals(1, l.finished)
  }

  @Test fun lecteur_vide_entre_deux_morceaux_ne_finit_pas_trop_tot() {
    // le reseau est plus lent que la lecture : le lecteur se vide avant la phrase suivante
    val p = FakePlayer(); val l = L(); val q = TtsQueue(p, l)
    q.add(0, b("A"), false)
    p.drain()
    assertEquals(0, l.finished)
    q.add(1, b("B"), true)
    p.drain()
    assertEquals(1, l.finished)
    assertEquals(listOf("A", "B"), p.played)
  }

  @Test fun doublons_et_repli_tts_ignores() {
    // repli `tts` arrive apres coup avec seq 0 de nouveau : deja joue, ignore
    val p = FakePlayer(); val l = L(); val q = TtsQueue(p, l)
    q.add(0, b("A"), false)
    q.add(0, b("A"), false)
    q.add(1, b("B"), true)
    q.add(0, b("A"), true)
    assertEquals(listOf("A", "B"), p.played)
  }

  @Test fun stop_coupe_et_plus_rien_ne_passe() {
    val p = FakePlayer(); val l = L(); val q = TtsQueue(p, l)
    q.add(0, b("A"), false)
    q.stop()
    q.add(1, b("B"), true)
    p.drain()
    assertTrue(p.stopped)
    assertEquals(listOf("A"), p.played)
    assertEquals(0, l.finished)
    assertFalse(q.playing)
  }

  @Test fun abort_finit_apres_ce_qui_joue() {
    val p = FakePlayer(); val l = L(); val q = TtsQueue(p, l)
    q.add(0, b("A"), false)
    q.add(2, b("C"), false)  // seq 1 ne viendra jamais (tts_error)
    q.abort()
    assertEquals(0, l.finished)
    p.drain()
    assertEquals(1, l.finished)
    assertEquals(listOf("A"), p.played)
  }
}
