package dev.aura.mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallCardTest {
  private val now = 1_790_000_000L

  @Test fun carte_lue_depuis_le_json_du_telephone() {
    val raw = """{"title":"Dupont SA","lines":["Ticket #123","Dernier appel hier"],"number":"+33612345678","ts":1790000000,"autre":1}"""
    val c = Protocol.decode<CallCard>(raw.toByteArray())!!
    assertEquals("Dupont SA", c.title)
    assertEquals(2, c.lines.size)
    assertEquals("+33612345678", c.number)
  }

  @Test fun cinq_lignes_au_plus_texte_seul() {
    val c = CallCardLogic.prepare(CallCard("T", List(9) { "ligne $it" }), now)!!
    assertEquals(5, c.lines.size)
    val dirty = CallCardLogic.prepare(CallCard("Dupont‮txt\u0000", listOf("a\nb\tc", "‏⁦ok⁩", "   ")), now)!!
    assertFalse(dirty.title.contains('‮'))
    assertEquals(listOf("a b c", "ok"), dirty.lines)
  }

  @Test fun lignes_longues_tronquees() {
    val c = CallCardLogic.prepare(CallCard("T", listOf("a".repeat(500))), now)!!
    assertEquals(CallCardLogic.LINE_MAX, c.lines[0].length)
    assertTrue(c.lines[0].endsWith("…"))
  }

  @Test fun titre_absent_donne_numero_connu() {
    assertEquals("Numéro connu", CallCardLogic.prepare(CallCard("", listOf("x")), now)!!.title)
    assertEquals("Numéro connu", CallCardLogic.prepare(CallCard("   ", listOf("x")), now)!!.title)
  }

  @Test fun carte_vide_ou_absente_n_affiche_rien() {
    assertNull(CallCardLogic.prepare(null, now))
    assertNull(CallCardLogic.prepare(CallCard("", emptyList()), now))
    assertNull(CallCardLogic.prepare(CallCard("", listOf("  ")), now))
    assertNotNull(CallCardLogic.prepare(CallCard("Dupont", emptyList()), now))
  }

  @Test fun carte_perimee_ou_du_futur_est_ignoree() {
    assertNotNull(CallCardLogic.prepare(CallCard("T", listOf("x"), ts = now - 60), now))
    assertNotNull(CallCardLogic.prepare(CallCard("T", listOf("x"), ts = (now - 60) * 1000), now))  // ms
    assertNull(CallCardLogic.prepare(CallCard("T", listOf("x"), ts = now - 181), now))
    assertNull(CallCardLogic.prepare(CallCard("T", listOf("x"), ts = now + 3600), now))
    assertNotNull(CallCardLogic.prepare(CallCard("T", listOf("x"), ts = 0L), now))  // sans ts : recente
  }

  @Test fun un_lien_reste_du_texte() {
    val c = CallCardLogic.prepare(CallCard("T", listOf("https://evil.example/x")), now)!!
    assertEquals("https://evil.example/x", c.lines[0])
  }

  @Test fun fiche_partielle_lue_conservee_et_dite() {
    val c = Protocol.decode<CallCard>("""{"title":"ACME","lines":["🏢 ACME"],"partial":true}""".toByteArray())!!
    assertTrue(c.partial)
    val p = CallCardLogic.prepare(c, now)!!
    assertTrue("le nettoyage garde le drapeau", p.partial)
    assertTrue(CallCardLogic.body(p).endsWith(CallCardLogic.PARTIAL_NOTE))
    assertEquals("Fiche partielle · 🏢 ACME", CallCardLogic.summary(p))
    val full = CallCardLogic.prepare(CallCard("ACME", listOf("🏢 ACME")), now)!!
    assertFalse(full.partial)
    assertEquals("🏢 ACME", CallCardLogic.body(full))
    assertEquals("🏢 ACME", CallCardLogic.summary(full))
  }
}
