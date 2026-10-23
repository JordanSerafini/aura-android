package dev.aura.mobile.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Revue du 24/10 : `plan` nettoie `op` (trim) mais le plancher de confirmation le lisait brut.
 * Un bridge qui envoie `op: " tap"` avec `confirm: false` faisait toucher l'ecran sans confirmation.
 * Invariant : tout geste que `plan` accepte comme tap / type exige la confirmation locale.
 */
class UiOpPaddingTest {
  private fun req(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }

  private val padded = listOf(" tap", "tap ", "\ttap", "tap\n", " type", "type ", "\ntype\t")

  @Test fun un_op_entoure_d_espaces_exige_quand_meme_la_confirmation() {
    for (op in padded) {
      assertTrue("« $op »", UiGuard.needsConfirm("ui_act", req("op" to op)))
      assertTrue("« $op »", UiGuard.isUiOutgoing("ui_act", req("op" to op)))  // runLocal le refuse aussi
    }
  }

  @Test fun ce_que_plan_execute_comme_geste_sortant_est_toujours_confirme() {
    val whitelist = listOf("com.whatsapp")
    for (op in padded) {
      val p = req("app" to "com.whatsapp", "op" to op, "text" to "Envoyer")
      val plan = UiGuard.plan(p, whitelist)
      assertTrue(plan.op in setOf("tap", "type"))
      assertTrue("plan accepte « $op » : la confirmation doit suivre", UiGuard.needsConfirm("ui_act", p))
    }
  }

  @Test fun le_resume_de_confirmation_decrit_le_geste_reel_malgre_les_espaces() {
    assertEquals("Toucher « Envoyer » dans whatsapp",
      ConfirmSummary.build("ui_act", req("app" to "whatsapp", "op" to " tap", "text" to "Envoyer")))
    assertEquals("Saisir « bonjour » dans whatsapp",
      ConfirmSummary.build("ui_act", req("app" to "whatsapp", "op" to "type\n", "text" to "bonjour")))
  }
}
