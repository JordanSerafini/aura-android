package dev.aura.mobile.data

import dev.aura.mobile.protocol.ConfirmRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfirmQueueTest {
  private val now = 1_790_000_000_000L
  private fun p(id: String, deadline: Long) = PendingConfirm(ConfirmRequest(id, "résumé $id", 30), deadline)

  @Test fun la_plus_ancienne_non_echue_est_rouverte() {
    var list = emptyList<PendingConfirm>()
    list = ConfirmQueue.add(list, p("b", now + 20_000), now)
    list = ConfirmQueue.add(list, p("a", now + 5_000), now)
    list = ConfirmQueue.add(list, p("c", now + 50_000), now)
    assertEquals("a", ConfirmQueue.oldestLive(list, now)?.request?.actionId)
    // « a » échue : on passe à la suivante
    assertEquals("b", ConfirmQueue.oldestLive(list, now + 6_000)?.request?.actionId)
    assertNull(ConfirmQueue.oldestLive(list, now + 60_000))
  }

  @Test fun meme_action_id_remplace_sans_doublon() {
    var list = ConfirmQueue.add(emptyList(), p("a", now + 5_000), now)
    list = ConfirmQueue.add(list, p("a", now + 9_000), now)
    assertEquals(1, list.size)
    assertEquals(now + 9_000, list.single().deadlineMs)
  }

  @Test fun retrait_et_recherche() {
    val list = ConfirmQueue.add(ConfirmQueue.add(emptyList(), p("a", now + 5_000), now), p("b", now + 6_000), now)
    assertEquals(listOf("b"), ConfirmQueue.remove(list, "a").map { it.request.actionId })
    assertEquals("b", ConfirmQueue.find(list, "b")?.request?.actionId)
    assertNull(ConfirmQueue.find(list, "z"))
  }

  @Test fun les_demandes_echues_tombent_a_l_ajout_et_la_liste_est_bornee() {
    var list = ConfirmQueue.add(emptyList(), p("vieille", now - 1), now - 10_000)
    list = ConfirmQueue.add(list, p("neuve", now + 1_000), now)
    assertEquals(listOf("neuve"), list.map { it.request.actionId })
    var many = emptyList<PendingConfirm>()
    for (i in 1..25) many = ConfirmQueue.add(many, p("x$i", now + i * 1_000L), now)
    assertEquals(ConfirmQueue.MAX, many.size)
    assertEquals(0, ConfirmQueue.prune(many, now + 100_000).size)
  }
}
