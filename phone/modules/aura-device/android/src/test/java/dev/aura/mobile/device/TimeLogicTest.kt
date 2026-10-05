package dev.aura.mobile.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeLogicTest {
  private fun items(json: String) = TimeLogic.parseProposals(JSONObject(json))

  private val TWO = """{"type":"time_proposals","ts":1790000000.5,"items":[
    {"id":"2026-10-01-1001","ticket_id":1001,"title":"Imprimante hors ligne","client":"ACME Test","minutes":30,
     "evidence":"fenêtre du ticket ouverte 22 min + appel 6 min","copy_text":"Temps passé : 30 min (fenêtre du ticket ouverte 22 min + appel 6 min)."},
    {"id":"2026-10-01-1002","ticket_id":1002,"title":"Mot de passe oublié","client":"ACME Test","minutes":20,
     "evidence":"fenêtre du ticket ouverte 20 min","copy_text":"Temps passé : 20 min (fenêtre du ticket ouverte 20 min)."}]}"""

  @Test fun propositions_lues_avec_leur_total() {
    val l = items(TWO)
    assertEquals(2, l.size)
    assertEquals("1001", l[0].ticket)
    assertEquals(30, l[0].minutes)
    assertEquals(50, TimeLogic.total(l))
  }

  @Test fun notification_n_temps_a_valider_avec_les_minutes() {
    assertEquals("2 temps à valider (50 min)", TimeLogic.summary(items(TWO)))
    val one = items("""{"items":[{"id":"2026-10-01-1","minutes":45,"title":"t"}]}""")
    assertEquals("1 temps à valider (45 min)", TimeLogic.summary(one))
  }

  @Test fun rien_a_proposer_donne_une_liste_vide() {
    assertTrue(items("""{"items":[]}""").isEmpty())
    assertTrue(items("""{"type":"time_proposals"}""").isEmpty())
  }

  @Test fun id_mal_forme_duree_folle_et_doublon_sont_ecartes() {
    val l = items("""{"items":[
      {"id":"1001","minutes":10},
      {"id":"2026-10-01-1001","minutes":0},
      {"id":"2026-10-01-1001","minutes":481},
      {"id":"2026-10-01-1001","minutes":15},
      {"id":"2026-10-01-1001","minutes":99},
      {"id":"2026-10-01-a/b","minutes":10},
      {"id":"2026-10-01-1003","minutes":"30"},
      {"id":"2026-10-01-16612","minutes":5}]}""")
    assertEquals(listOf("2026-10-01-1001", "2026-10-01-16612"), l.map { it.id })
    assertEquals(15, l[0].minutes)  // le premier id valide gagne, le doublon est ignore
  }

  @Test fun quinze_propositions_au_plus() {
    val many = (1..30).joinToString(",") { """{"id":"2026-10-01-${1000 + it}","minutes":5}""" }
    assertEquals(TimeLogic.MAX_ITEMS, items("""{"items":[$many]}""").size)
  }

  @Test fun textes_de_tiers_en_texte_seul_et_bornes() {
    val l = items("""{"items":[{"id":"2026-10-01-7","minutes":10,"title":"Titre‮ inversé\n\tsur 2 lignes","client":"${"C".repeat(300)}","evidence":"e"}]}""")
    assertFalse(l[0].title.contains('‮'))
    assertFalse(l[0].title.contains('\n'))
    assertTrue(l[0].client.length <= 60)
  }

  @Test fun ids_a_confirmer_sont_exactement_ceux_affiches_dans_la_limite_du_bridge() {
    assertEquals(listOf("2026-10-01-1001", "2026-10-01-1002"), TimeLogic.confirmIds(items(TWO)))
    val over = (1..40).map { TimeItem("2026-10-01-$it", "$it", "t", "c", 5, "", "") }
    assertEquals(TimeLogic.CONFIRM_MAX, TimeLogic.confirmIds(over).size)
  }

  @Test fun message_time_confirm() {
    val m = TimeLogic.confirmMessage(listOf("2026-10-01-1001", "2026-10-01-1002"))
    assertEquals("time_confirm", m.getString("type"))
    assertEquals(2, m.getJSONArray("ids").length())
    assertEquals("2026-10-01-1002", m.getJSONArray("ids").getString(1))
  }

  // ─── time_result ────────────────────────────────────────────────────────

  private val RESULT = """{"type":"time_result","ts":1790000001.0,"ok":["2026-10-01-1001"],
    "failed":[{"id":"2026-10-01-1002","reason":"écriture dans le système de tickets non disponible",
    "copy_text":"Temps passé : 20 min (fenêtre du ticket ouverte 20 min)."}]}"""

  @Test fun resultat_succes_et_echecs() {
    val r = TimeLogic.parseResult(JSONObject(RESULT))!!
    assertEquals(listOf("2026-10-01-1001"), r.ok)
    assertEquals("écriture dans le système de tickets non disponible", r.failed.single().reason)
    assertEquals("1 saisi, 1 en échec", TimeLogic.resultTitle(r))
    assertEquals("#1002 : écriture dans le système de tickets non disponible", TimeLogic.resultText(r))
  }

  @Test fun titres_de_resultat() {
    fun r(json: String) = TimeLogic.parseResult(JSONObject(json))!!
    assertEquals("1 temps saisi", TimeLogic.resultTitle(r("""{"ok":["2026-10-01-1"],"failed":[]}""")))
    assertEquals("3 temps saisis", TimeLogic.resultTitle(r("""{"ok":["2026-10-01-1","2026-10-01-2","2026-10-01-3"],"failed":[]}""")))
    assertEquals("Temps non saisi", TimeLogic.resultTitle(r("""{"ok":[],"failed":[{"id":"2026-10-01-1","reason":"déjà saisi"}]}""")))
    assertEquals("2 temps non saisis", TimeLogic.resultTitle(r("""{"ok":[],"failed":[{"id":"2026-10-01-1","reason":"x"},{"id":"2026-10-01-2","reason":"y"}]}""")))
  }

  @Test fun copier_prend_le_texte_de_l_echec() {
    val r = TimeLogic.parseResult(JSONObject(RESULT))!!
    assertEquals("Temps passé : 20 min (fenêtre du ticket ouverte 20 min).", TimeLogic.copyBlock(r))
  }

  @Test fun copier_plusieurs_echecs_precise_le_ticket() {
    val r = TimeLogic.parseResult(JSONObject("""{"ok":[],"failed":[
      {"id":"2026-10-01-1002","reason":"a","copy_text":"Temps passé : 20 min."},
      {"id":"2026-10-01-1003","reason":"b","copy_text":"Temps passé : 5 min."}]}"""))!!
    assertEquals("#1002 : Temps passé : 20 min.\n#1003 : Temps passé : 5 min.", TimeLogic.copyBlock(r))
  }

  @Test fun pas_de_bouton_copier_sans_texte_a_copier() {
    // « déjà saisi » et id inconnu n'ont pas de copy_text : rien a recopier, et surtout pas de ressaisie
    val r = TimeLogic.parseResult(JSONObject("""{"ok":[],"failed":[{"id":"2026-10-01-1","reason":"déjà saisi"},{"id":"?","reason":"proposition inconnue ou expirée"}]}"""))!!
    assertNull(TimeLogic.copyBlock(r))
    assertEquals("#1 : déjà saisi\nproposition inconnue ou expirée", TimeLogic.resultText(r))
  }

  @Test fun message_qui_n_est_pas_un_resultat() {
    assertNull(TimeLogic.parseResult(JSONObject("""{"type":"time_result"}""")))
    assertNotNull(TimeLogic.parseResult(JSONObject("""{"ok":[]}""")))
  }

  @Test fun succes_sans_echec_dit_que_c_est_enregistre() {
    assertEquals("C'est enregistré dans le système de tickets.", TimeLogic.resultText(TimeLogic.parseResult(JSONObject("""{"ok":["2026-10-01-1"],"failed":[]}"""))!!))
  }
}
