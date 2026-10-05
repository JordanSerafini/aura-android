package dev.aura.mobile.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VadTest {
  /** Rejoue une suite de (dBFS, duree ms) et rend les evenements non nuls avec leur instant. */
  private fun run(vad: Vad, vararg segments: Pair<Double, Int>): List<Pair<Vad.Event, Int>> {
    val out = mutableListOf<Pair<Vad.Event, Int>>()
    var t = 0
    for ((db, ms) in segments) {
      repeat(ms / vad.frameMs) {
        t += vad.frameMs
        val e = vad.feed(db)
        if (e != Vad.Event.NONE) out += e to t
      }
    }
    return out
  }

  @Test fun phrase_puis_800ms_de_silence_termine_l_enonce() {
    val vad = Vad()
    val ev = run(vad, -65.0 to 1000, -30.0 to 1500, -65.0 to 1000)
    assertEquals(listOf(Vad.Event.START, Vad.Event.END_SILENCE), ev.map { it.first })
    // fin exactement 800 ms apres la derniere trame de voix (1000 + 1500 + 800)
    assertEquals(3300, ev[1].second)
  }

  @Test fun une_pause_de_600ms_ne_coupe_pas_la_phrase() {
    val vad = Vad()
    val ev = run(vad, -65.0 to 1000, -30.0 to 800, -65.0 to 600, -30.0 to 800, -65.0 to 1000)
    assertEquals(listOf(Vad.Event.START, Vad.Event.END_SILENCE), ev.map { it.first })
  }

  @Test fun un_clic_court_est_un_faux_depart() {
    val vad = Vad()
    val ev = run(vad, -65.0 to 1000, -30.0 to 100, -65.0 to 1200)
    assertEquals(listOf(Vad.Event.START, Vad.Event.DISCARD), ev.map { it.first })
  }

  @Test fun soixante_secondes_maximum() {
    val vad = Vad()
    val ev = run(vad, -65.0 to 400, -30.0 to 61_000)
    assertEquals(Vad.Event.END_MAX, ev[1].first)
    assertEquals(400 + 60_000, ev[1].second)
  }

  @Test fun seuil_adaptatif_piece_bruyante() {
    // bruit de fond a -40 dBFS (cafe) : une voix a -36 (bruit + 4 dB) n'est pas de la parole,
    // une voix a -25 (bruit + 15) en est
    val vad = Vad()
    assertTrue(run(vad, -40.0 to 2000, -36.0 to 1000).isEmpty())
    assertEquals(Vad.Event.START, run(vad, -25.0 to 500).first().first)
  }

  @Test fun le_bruit_redescend_quand_la_piece_se_calme() {
    val vad = Vad()
    run(vad, -35.0 to 1000)
    run(vad, -70.0 to 1500)
    assertTrue("bruit estime ${vad.noiseDb}", vad.noiseDb < -60.0)
  }

  @Test fun mode_barge_ignore_l_echo_mais_pas_l_utilisateur() {
    val vad = Vad()
    run(vad, -65.0 to 1000)
    vad.mode = Vad.Mode.BARGE
    // echo residuel du haut-parleur a -45 dBFS : sous le plancher BARGE (-38)
    assertTrue(run(vad, -45.0 to 2000).isEmpty())
    // l'utilisateur reparle fort : detecte, mais apres 250 ms soutenues (pas 60)
    val ev = run(vad, -25.0 to 1000)
    assertEquals(Vad.Event.START, ev.first().first)
    assertEquals(260, ev.first().second)
  }

  @Test fun dbfs_d_une_sinusoide_pleine_echelle() {
    val s = ShortArray(320) { (Math.sin(it * 2 * Math.PI / 32) * 32767).toInt().toShort() }
    assertEquals(-3.0, Vad.dbfs(s), 0.2)
    assertEquals(-100.0, Vad.dbfs(ShortArray(320)), 0.0)
  }
}
