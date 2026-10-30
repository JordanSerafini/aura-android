package dev.aura.mobile.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Revue securite du 27/10, N4 : un action_id en double ne remplace jamais la demande en cours. */
class IdTableTest {
  private class P(val notifId: Int)

  @Test fun un_id_deja_pris_est_refuse_et_la_demande_en_place_ne_bouge_pas() {
    val t = IdTable<P>()
    val a = P(1)
    val b = P(2)
    assertTrue(t.claim("x", a))
    assertFalse(t.claim("x", b))
    assertSame(a, t["x"])
    assertEquals(1, t.size)
  }

  @Test fun release_ne_retire_que_sa_propre_demande() {
    val t = IdTable<P>()
    val a = P(1)
    val b = P(2)
    t.claim("x", a)
    assertTrue(t.release("x", a))
    assertTrue(t.claim("x", b))  // id libere : une NOUVELLE demande est permise
    assertFalse(t.release("x", a))  // l'echeance tardive de A ne retire pas B
    assertSame(b, t["x"])
  }

  @Test fun takeIf_lie_la_reponse_a_la_carte_qui_l_a_portee() {
    val t = IdTable<P>()
    val b = P(7)
    t.claim("x", b)
    assertNull(t.takeIf("x") { it.notifId == 3 })  // « Accepter » d'une ancienne carte (notification 3) : sans effet
    assertSame(b, t["x"])
    assertSame(b, t.takeIf("x") { it.notifId == 7 })
    assertNull(t["x"])
    assertNull(t.take("x"))
  }

  private fun src(name: String) = File("src/main/java/dev/aura/mobile/device/$name").readText()
    .lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")

  @Test fun confirmations_et_actions_en_cours_passent_par_la_table_sans_ecrasement() {
    val cm = src("ConfirmManager.kt")
    assertFalse("ConfirmManager ecrase une demande : pending[id] = …", Regex("""pending\[[^\]]+\]\s*=""").containsMatchIn(cm))
    assertTrue(cm.contains("pending.claim("))
    assertTrue("la notification lie « Accepter » a SA carte", cm.contains("\"notif_id\""))
    val ex = src("ActionExecutor.kt")
    assertFalse("ActionExecutor ecrase une action en cours : running[id] = …", Regex("""running\[[^\]]+\]\s*=""").containsMatchIn(ex))
    assertTrue(ex.contains("running.claim(") && ex.contains("\"duplicate_id\""))
    assertTrue(ex.contains("running.release("))
    assertTrue("le bouton de la notification transmet notif_id", src("Notifs.kt").contains("getStringExtra(\"notif_id\")"))
  }
}
