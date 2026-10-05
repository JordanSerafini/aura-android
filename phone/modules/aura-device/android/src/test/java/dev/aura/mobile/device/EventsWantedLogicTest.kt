package dev.aura.mobile.device

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventsWantedLogicTest {
  private fun welcome(json: String) = JSONObject(json)

  @Test fun le_welcome_donne_la_liste_des_types_voulus() {
    val set = EventsWantedLogic.fromWelcome(welcome("""{"type":"welcome","events_wanted":["missed_call","battery_low","call_ringing"]}"""))
    assertEquals(setOf("missed_call", "battery_low", "call_ringing"), set)
  }

  @Test fun welcome_sans_le_champ_n_est_pas_une_liste_vide() {
    // bridge d'avant le 01/10 soir : aucune information, donc tout part comme avant
    assertNull(EventsWantedLogic.fromWelcome(welcome("""{"type":"welcome"}""")))
    assertTrue(EventsWantedLogic.allows(null, "charger"))
  }

  @Test fun liste_vide_veut_dire_aucune_regle_active() {
    val set = EventsWantedLogic.fromWelcome(welcome("""{"events_wanted":[]}"""))!!
    assertTrue(set.isEmpty())
    for (k in EventsWantedLogic.KNOWN) assertFalse("$k doit etre filtre", EventsWantedLogic.allows(set, k))
  }

  @Test fun charger_est_filtre_tant_qu_aucune_regle_ne_le_veut() {
    val set = EventsWantedLogic.fromMessage(JSONObject("""{"type":"events_wanted","kinds":["missed_call","call_ringing"],"ts":1.5}"""))
    assertFalse(EventsWantedLogic.allows(set, "charger"))
    assertFalse(EventsWantedLogic.allows(set, "zone"))
  }

  @Test fun appels_manques_et_sonnerie_passent_tant_qu_une_regle_les_veut() {
    val set = EventsWantedLogic.fromMessage(JSONObject("""{"kinds":["missed_call","call_ringing"]}"""))
    assertTrue(EventsWantedLogic.allows(set, "missed_call"))
    assertTrue(EventsWantedLogic.allows(set, "call_ringing"))
  }

  @Test fun allumer_une_regle_est_vu_au_message_suivant() {
    val before = EventsWantedLogic.fromMessage(JSONObject("""{"kinds":["missed_call"]}"""))
    val after = EventsWantedLogic.fromMessage(JSONObject("""{"kinds":["missed_call","charger"]}"""))
    assertFalse(EventsWantedLogic.allows(before, "charger"))
    assertTrue(EventsWantedLogic.allows(after, "charger"))
  }

  @Test fun une_liste_illisible_ne_coupe_rien() {
    assertNull(EventsWantedLogic.fromMessage(JSONObject("""{"kinds":"charger"}""")))
    assertNull(EventsWantedLogic.fromMessage(JSONObject("""{"kinds":null}""")))
    assertNull(EventsWantedLogic.fromMessage(JSONObject("""{}""")))
    assertNull(EventsWantedLogic.fromWelcome(welcome("""{"events_wanted":{"a":1}}""")))
  }

  @Test fun elements_mal_formes_ignores() {
    val set = EventsWantedLogic.parse(JSONArray("""["missed_call", 3, null, "Charger", "a b", "", "zone"]"""))!!
    assertEquals(setOf("missed_call", "zone"), set)
  }

  @Test fun persistance_aller_retour_et_prefs_illisibles() {
    val json = EventsWantedLogic.toJson(setOf("charger", "missed_call", "futur_type"))
    assertEquals("""["missed_call","charger","futur_type"]""", json)
    assertEquals(setOf("missed_call", "charger", "futur_type"), EventsWantedLogic.fromPrefs(json))
    assertEquals(emptySet<String>(), EventsWantedLogic.fromPrefs("[]"))
    assertNull(EventsWantedLogic.fromPrefs(null))
    assertNull(EventsWantedLogic.fromPrefs("pas du json"))
  }
}
