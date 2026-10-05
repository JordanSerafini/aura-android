package dev.aura.mobile.device

import org.json.JSONArray
import org.json.JSONObject

/*
 * `events_wanted` (PROTOCOL.md §11.14) : le bridge donne la liste des types de phone_event qui ont au moins une règle
 * active ; le téléphone ne transmet que ceux-là. Logique pure, testée en JVM (EventsWantedLogicTest) ; EventsWanted.kt
 * en est la colle (prefs, welcome, message diffusé).
 *
 *  - `null` = aucune information (ancien bridge, jamais connecté) : tout part, comme avant ;
 *  - ensemble vide = aucune règle active : plus aucun phone_event ;
 *  - une liste illisible n'est PAS une liste vide : on garde ce qu'on savait (on ne coupe pas sur une erreur).
 */
object EventsWantedLogic {
  private val KIND = Regex("^[a-z_]{1,30}$")

  /** Les types connus de l'app (EventSettings.allows) ; un type inconnu du bridge est gardé tel quel mais ne sert à rien ici. */
  val KNOWN = listOf("missed_call", "notification", "zone", "battery_low", "charger", "call_ringing")

  /** Liste du bridge -> ensemble ; null si le champ est absent ou n'est pas un tableau. Éléments mal formés ignorés. */
  fun parse(arr: JSONArray?): Set<String>? {
    if (arr == null) return null
    val out = linkedSetOf<String>()
    for (i in 0 until arr.length()) {
      val v = arr.opt(i)
      if (v is String && KIND.matches(v)) out += v
    }
    return out
  }

  /** `welcome.events_wanted`. */
  fun fromWelcome(msg: JSONObject): Set<String>? = parse(msg.optJSONArray("events_wanted"))

  /** Message diffusé `{type: "events_wanted", kinds: [...]}`. */
  fun fromMessage(msg: JSONObject): Set<String>? = parse(msg.optJSONArray("kinds"))

  /** Cet événement doit-il partir ? Sans information, oui. */
  fun allows(wanted: Set<String>?, kind: String): Boolean = wanted == null || kind in wanted

  fun toJson(wanted: Set<String>): String = JSONArray(KNOWN.filter { it in wanted } + (wanted - KNOWN.toSet()).sorted()).toString()

  /** Relit l'ensemble persisté ; null (jamais enregistré, ou illisible) = pas d'information. */
  fun fromPrefs(raw: String?): Set<String>? {
    if (raw == null) return null
    return try { parse(JSONArray(raw)) } catch (e: Exception) { null }
  }
}
