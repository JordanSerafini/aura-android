package dev.aura.mobile.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkNonceTest {
  @Test fun un_jeton_emis_est_consomme_une_seule_fois() {
    val n = TalkNonce()
    val t = n.issue(1_000L)
    assertEquals(32, t.length)
    assertTrue(t.all { it in "0123456789abcdef" })
    assertTrue(n.consume(t, 2_000L))
    assertFalse("usage unique", n.consume(t, 2_001L))
  }

  @Test fun sans_jeton_emis_rien_ne_passe() {
    val n = TalkNonce()
    assertFalse(n.consume(null, 0L))
    assertFalse(n.consume("", 0L))
    assertFalse(n.consume("0".repeat(32), 0L))  // lien forge par une autre app
  }

  @Test fun jeton_faux_refuse_sans_bruler_le_vrai() {
    val n = TalkNonce()
    val t = n.issue(0L)
    assertFalse(n.consume("f".repeat(32), 10L))
    assertFalse(n.consume(t.dropLast(1), 10L))
    assertTrue(n.consume(t, 20L))
  }

  @Test fun jeton_expire_apres_30_secondes() {
    val n = TalkNonce()
    val t = n.issue(0L)
    assertFalse(n.consume(t, TalkNonce.TTL_MS + 1))
    assertFalse("expire = definitivement perdu", n.consume(t, 5L))
    val t2 = n.issue(100_000L)
    assertTrue(n.consume(t2, 100_000L + TalkNonce.TTL_MS))
  }

  @Test fun un_nouveau_jeton_remplace_l_ancien_et_deux_emissions_different() {
    val n = TalkNonce()
    val a = n.issue(0L)
    val b = n.issue(1L)
    assertNotEquals(a, b)
    assertFalse(n.consume(a, 2L))
    assertTrue(n.consume(b, 2L))
  }

  @Test fun aleatoire_injectable_pour_les_tests() {
    val n = TalkNonce(random = { ByteArray(16) { it.toByte() } })
    assertEquals("000102030405060708090a0b0c0d0e0f", n.issue(0L))
  }
}
