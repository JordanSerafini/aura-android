package dev.aura.mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordGateTest {
  private class Mem(var value: String? = null)

  private fun gate(m: Mem = Mem(), next: () -> String = { "a".repeat(32) }) =
    RecordGate(read = { m.value }, write = { m.value = it }, random = next)

  @Test fun le_jeton_de_la_tuile_autorise_l_enregistrement() {
    val g = gate()
    val t = g.token()
    assertTrue(g.allows(t))
    assertTrue("pas a usage unique : la tuile affichee peut etre touchee plusieurs fois", g.allows(t))
  }

  @Test fun sans_jeton_ou_avec_un_mauvais_jeton_rien_ne_demarre() {
    val g = gate()
    g.token()
    assertFalse(g.allows(null))        // `record=true` envoye par une autre app : pas de jeton
    assertFalse(g.allows(""))
    assertFalse(g.allows("b".repeat(32)))
    assertFalse(g.allows("a".repeat(31)))
  }

  @Test fun aucun_jeton_emis_aucune_autorisation() {
    val g = gate()  // la tuile n'a jamais ete rendue
    assertFalse(g.allows("a".repeat(32)))
    assertFalse(g.allows(null))
  }

  @Test fun le_jeton_est_stable_et_survit_au_processus() {
    val m = Mem()
    var calls = 0
    val first = gate(m) { calls++; "c".repeat(31) + calls }.token()
    // « nouveau processus » : meme stockage, nouvelle garde
    val second = gate(m) { calls++; "d".repeat(32) }
    assertEquals(first, second.token())
    assertTrue(second.allows(first))
    assertEquals(1, calls)
  }

  @Test fun jeton_stocke_trop_court_est_remplace() {
    val m = Mem("court")
    val g = gate(m)
    assertFalse(g.allows("court"))
    val t = g.token()
    assertEquals("a".repeat(32), t)
    assertTrue(g.allows(t))
  }
}
