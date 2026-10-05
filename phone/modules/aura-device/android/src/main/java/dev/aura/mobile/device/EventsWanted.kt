package dev.aura.mobile.device

import android.util.Log
import org.json.JSONObject

/**
 * Types d'événements que le bridge veut recevoir (`events_wanted`, PROTOCOL.md §11.14), gardés dans les prefs : un
 * téléphone hors ligne au démarrage, ou un bridge sans le champ, garde donc le dernier ensemble connu ; à défaut, tout part.
 * La liste est relue à CHAQUE message (une règle allumée fait arriver la liste avant le premier événement utile).
 */
object EventsWanted {
  private const val PREF = "events_wanted"

  @Volatile private var cached: Set<String>? = null
  @Volatile private var loaded = false

  fun current(): Set<String>? {
    if (!loaded) {
      cached = EventsWantedLogic.fromPrefs(Aura.prefs.getString(PREF, null))
      loaded = true
    }
    return cached
  }

  fun allows(kind: String): Boolean = EventsWantedLogic.allows(current(), kind)

  private fun adopt(set: Set<String>?, origin: String) {
    if (set == null) return  // champ absent ou illisible : on garde ce qu'on savait
    cached = set
    loaded = true
    Aura.prefs.edit().putString(PREF, EventsWantedLogic.toJson(set)).apply()
    Log.i(Aura.TAG, "events_wanted ($origin) : ${set.sorted()}")
  }

  fun onWelcome(msg: JSONObject) = adopt(EventsWantedLogic.fromWelcome(msg), "welcome")

  fun onMessage(msg: JSONObject) = adopt(EventsWantedLogic.fromMessage(msg), "message")
}
