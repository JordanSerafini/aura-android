package dev.aura.mobile.device

import android.util.Log
import org.json.JSONObject

/**
 * Journal Aura unifie (JournalLogic.kt) : evenements du telephone, actions, gestes. Ecrit AU MOMENT ou PhoneEvents
 * et ActionExecutor traitent (avant : logcat seulement pour les evenements). Persiste dans les prefs, evenement
 * `onLog` vers le JS a chaque entree.
 */
object Journal {
  private const val PREF = "journal"
  private const val PREF_LEGACY = "log"  // ancien journal des actions (100 entrees), repris une fois

  private val log = JournalLog({ raw ->
    try {
      Aura.prefs.edit().putString(PREF, raw).apply()
    } catch (e: Exception) {
      Log.w(Aura.TAG, "journal non ecrit", e)
    }
  })
  private var loaded = false
  /** Detail laisse par le service d'accessibilite pour l'entree d'action en cours sur CE thread. */
  private val detail = ThreadLocal<Pair<String, String>?>()

  @Synchronized
  private fun ensureLoaded() {
    if (loaded) return
    loaded = true
    val saved = Aura.prefs.getString(PREF, null)
    if (saved != null) log.load(saved)
    else Aura.prefs.getString(PREF_LEGACY, null)?.let { log.load(it, fromLegacy = true) }
  }

  fun add(entry: JSONObject) {
    if (!entry.has("ts")) entry.put("ts", System.currentTimeMillis())
    ensureLoaded()
    log.add(entry)
    Aura.emit("onLog", entry.toString())
  }

  fun json(): String {
    ensureLoaded()
    return log.json()
  }

  /** « Effacer le journal » (l'utilisateur, avec confirmation cote JS) : liste vide, ancien journal compris. */
  fun clear() {
    ensureLoaded()
    log.clear()
    Aura.prefs.edit().remove(PREF_LEGACY).apply()
    Aura.emit("onLogClear", "{}")
  }

  /** Un phone_event traite : `status` = JournalLogic.SENT, QUEUED, OFFLINE, FILTERED, BLOCKED, PAUSED. */
  fun event(kind: String, fields: JSONObject, status: String, detail: String? = null) {
    add(JournalLogic.eventEntry(kind, fields, status, detail))
  }

  /** Le service d'accessibilite dit quelle app et quel geste (ui_act) : repris par l'entree d'action de ce thread. */
  fun noteGesture(app: String, op: String) {
    detail.set(app to op)
  }

  fun takeGesture(): Pair<String, String>? = detail.get().also { detail.remove() }
}
