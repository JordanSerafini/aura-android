package dev.aura.mobile.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MissedLogicTest {
  private fun card(json: String) = MissedLogic.parse(JSONObject(json))!!

  private val ONE = """{"type":"missed_calls_card","reason":"recap","ts":1790000000.5,
    "groups":[{"number":"+33199001122","name":"ACME Test","count":5,"last_ts":1789999000.0,
    "actions":[{"label":"Rappeler","tel":"+33199001122"}]}]}"""

  @Test fun une_carte_du_bridge_est_lue() {
    val c = card(ONE)
    assertEquals("recap", c.reason)
    assertNull(c.paused)
    val g = c.groups.single()
    assertEquals("+33199001122", g.number)
    assertEquals("ACME Test", g.name)
    assertEquals(5, g.count)
    assertEquals(1_789_999_000L, g.lastTs)
    assertTrue(g.dialable)
  }

  @Test fun titre_appels_manques_et_nom_ou_numero() {
    val g = card(ONE).groups.single()
    assertEquals("5 appels manqués : ACME Test", MissedLogic.title(g))
    val anonyme = card("""{"groups":[{"number":"+33199001122","count":1}]}""").groups.single()
    assertEquals("1 appel manqué : +33 1 99 00 11 22", MissedLogic.title(anonyme))
    assertEquals("+41221234567", MissedLogic.pretty("+41221234567"))
  }

  @Test fun l_heure_du_dernier_appel() {
    val g = card(ONE).groups.single()
    assertEquals("Dernier à 13:56", MissedLogic.lastText(g, ZoneId.of("UTC")))
    assertEquals("Dernier à 15:56", MissedLogic.lastText(g, ZoneId.of("Europe/Paris")))
    assertEquals("", MissedLogic.lastText(card("""{"groups":[{"number":"+33199001122","count":2}]}""").groups.single()))
  }

  @Test fun numero_valide_par_regex_stricte_avant_tout_tel() {
    for (ok in listOf("+33199001122", "0199001122", "+41221234567", "123456")) assertTrue(ok, MissedLogic.dialable(ok))
    // ce qui permettrait d'injecter un autre schema ou des chiffres de code USSD n'ouvre jamais le composeur
    for (bad in listOf("", "12345", "+3319900112233445", "tel:+33199001122", "+33 1 99 00 11 22", "*#06#", "+33;ext=1", "0199001122\n", "javascript:1", "+", "++33199001122"))
      assertFalse("« $bad » ne doit pas etre appelable", MissedLogic.dialable(bad))
  }

  @Test fun groupe_au_numero_louche_garde_son_nom_mais_sans_bouton() {
    val c = card("""{"groups":[{"number":"+33;ext=1","name":"Standard Test","count":2},{"number":"abc","count":1}]}""")
    assertEquals(1, c.groups.size)  // le 2e n'a ni numero valide ni nom : rien a afficher
    val g = c.groups.single()
    assertFalse(g.dialable)
    assertEquals("", g.number)
    assertEquals("Standard Test", MissedLogic.who(g))
  }

  @Test fun nom_de_tiers_en_texte_seul() {
    val c = card("""{"groups":[{"number":"+33199001122","name":"  Jean‮Evil\n\tTest  ","count":1}]}""")
    val name = c.groups.single().name!!
    assertFalse(name.contains('‮'))
    assertFalse(name.contains('\n'))
    assertEquals("Jean Evil Test", name)
    val long = card("""{"groups":[{"number":"+33199001122","name":"${"x".repeat(200)}","count":1}]}""").groups.single().name!!
    assertTrue(long.length <= MissedLogic.NAME_MAX)
  }

  @Test fun carte_sans_groups_n_est_pas_une_carte_et_groups_vide_est_permis() {
    assertNull(MissedLogic.parse(JSONObject("""{"type":"missed_calls_card","ts":1}""")))
    assertTrue(card("""{"groups":[]}""").groups.isEmpty())
  }

  @Test fun dix_groupes_au_plus_et_sans_doublon() {
    val many = (1..14).joinToString(",") { """{"number":"+3319900${1000 + it}","count":1}""" }
    assertEquals(MissedLogic.MAX_GROUPS, card("""{"groups":[$many]}""").groups.size)
    val dup = card("""{"groups":[{"number":"+33199001122","count":1},{"number":"+33199001122","count":2}]}""")
    assertEquals(1, dup.groups.size)
  }

  @Test fun compte_borne_et_raison_par_defaut() {
    val g = card("""{"groups":[{"number":"+33199001122","count":-4}]}""").groups.single()
    assertEquals(1, g.count)
    assertEquals(999, card("""{"groups":[{"number":"+33199001122","count":123456}]}""").groups.single().count)
    assertEquals("recap", card("""{"reason":"bizarre","groups":[]}""").reason)
  }

  @Test fun mode_de_pause_porte_par_la_carte() {
    assertEquals("all", card("""{"paused":"all","groups":[]}""").paused)
    assertEquals("apps", card("""{"paused":"apps","groups":[]}""").paused)
    assertNull(card("""{"paused":"n_importe_quoi","groups":[]}""").paused)
    assertNull(card("""{"paused":null,"groups":[]}""").paused)
  }

  // ─── Plan : quoi afficher, quoi retirer ─────────────────────────────────

  @Test fun nouveau_numero_alerte_et_numero_deja_la_ne_realerte_pas() {
    val c = card("""{"reason":"burst","groups":[{"number":"+33199001122","count":3},{"number":"+33199003344","count":1}]}""")
    val plan = MissedLogic.plan(mapOf("+33199001122" to 3), c)
    assertEquals(listOf(false, true), plan.post.map { it.alert })
    assertTrue(plan.cancel.isEmpty())
  }

  @Test fun un_appel_de_plus_realerte() {
    val c = card("""{"reason":"burst","groups":[{"number":"+33199001122","count":4}]}""")
    assertTrue(MissedLogic.plan(mapOf("+33199001122" to 3), c).post.single().alert)
  }

  @Test fun numero_rappele_disparait_quand_le_bridge_renvoie_la_carte_sans_lui() {
    val c = card("""{"reason":"recap","groups":[{"number":"+33199003344","count":1}]}""")
    val plan = MissedLogic.plan(mapOf("+33199001122" to 5, "+33199003344" to 1), c)
    assertEquals(setOf("+33199001122"), plan.cancel)
  }

  @Test fun carte_vide_retire_tout() {
    val plan = MissedLogic.plan(mapOf("+33199001122" to 5, "+33199003344" to 1), card("""{"reason":"list","groups":[]}"""))
    assertEquals(setOf("+33199001122", "+33199003344"), plan.cancel)
    assertTrue(plan.post.isEmpty())
  }

  @Test fun reponse_a_missed_list_ne_ressuscite_rien_et_ne_fait_pas_de_bruit() {
    val c = card("""{"reason":"list","groups":[{"number":"+33199001122","count":5},{"number":"+33199003344","count":2}]}""")
    val plan = MissedLogic.plan(mapOf("+33199001122" to 4), c)  // le 2e a ete balaye par l'utilisateur
    assertEquals(1, plan.post.size)
    assertEquals("+33199001122", plan.post[0].group.number)
    assertFalse(plan.post[0].alert)
  }

  @Test fun pause_totale_rend_la_notification_discrete_sans_la_cacher() {
    val c = card("""{"paused":"all","groups":[{"number":"+33199001122","count":2}]}""")
    assertTrue(MissedLogic.quiet(c, PauseMode.OFF))
    assertTrue(MissedLogic.quiet(card("""{"groups":[]}"""), PauseMode.ALL))   // etat local : meme regle
    assertFalse(MissedLogic.quiet(card("""{"paused":"apps","groups":[]}"""), PauseMode.APPS))
    assertFalse(MissedLogic.quiet(card("""{"groups":[]}"""), PauseMode.OFF))
  }

  @Test fun nombre_de_numeros_a_rappeler_pour_la_montre() {
    assertEquals(2, MissedLogic.callbacks(card("""{"groups":[{"number":"+33199001122","count":5},{"number":"+33199003344","count":1}]}""")))
    assertEquals(0, MissedLogic.callbacks(card("""{"groups":[]}""")))
    assertNotNull(card(ONE))
  }
}
