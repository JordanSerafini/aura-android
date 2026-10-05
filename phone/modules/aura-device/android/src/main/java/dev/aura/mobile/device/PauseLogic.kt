package dev.aura.mobile.device

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * « Pause d'Aura » : arret d'urgence, logique pure (sans Android), testee en JVM (PauseLogicTest).
 * Pause.kt en est la colle (SharedPreferences, WebSocket, notification). Contrat : docs/PROTOCOL.md §10.
 *
 *  - off  : normal ;
 *  - apps : screen_read et ui_act refuses (plus aucune lecture ni aucun geste dans une app) ; tout le reste marche ;
 *  - all  : toute action sur le telephone refusee sauf device_status, et plus aucun phone_event envoye.
 *
 * Le bridge est l'autorite cote serveur ; l'app applique l'etat LOCALEMENT, meme hors ligne.
 */
object PauseMode {
  const val OFF = "off"
  const val APPS = "apps"
  const val ALL = "all"

  fun valid(m: String?): Boolean = m == OFF || m == APPS || m == ALL
}

/**
 * `until` : epoch secondes, 0 = jusqu'a la reprise. `changedAt` : ms du dernier changement LOCAL (dernier gagne).
 * `pending` : changement fait ici que le bridge n'a pas encore confirme (hors ligne) : renvoye au welcome.
 */
data class PauseState(
  val mode: String = PauseMode.OFF,
  val until: Long = 0L,
  val by: String = "",
  val changedAt: Long = 0L,
  val pending: Boolean = false,
) {
  fun active(nowS: Long): Boolean = mode != PauseMode.OFF && (until <= 0L || nowS < until)

  /** Mode en vigueur : une pause echue vaut `off`, sans attendre que quelqu'un l'ecrive. */
  fun effectiveMode(nowS: Long): String = if (active(nowS)) mode else PauseMode.OFF

  /** Forme stockee (prefs). */
  fun toJson(): JSONObject = JSONObject().put("mode", mode).put("until", until).put("by", by)
    .put("changed_at", changedAt).put("pending", pending)

  /** Forme du protocole (pause_state, /aura/status) : l'etat EN VIGUEUR a `nowS`. */
  fun toWire(nowS: Long): JSONObject {
    val eff = effectiveMode(nowS)
    return JSONObject().put("mode", eff).put("until", if (eff == PauseMode.OFF) 0L else until).put("by", by)
  }

  companion object {
    fun fromJson(raw: String?): PauseState {
      val j = try { JSONObject(raw ?: "{}") } catch (e: Exception) { return PauseState() }
      val mode = j.optString("mode", PauseMode.OFF)
      return PauseState(
        mode = if (PauseMode.valid(mode)) mode else PauseMode.OFF,
        until = j.optLong("until", 0L).coerceAtLeast(0L),
        by = j.optString("by", "").take(40),
        changedAt = j.optLong("changed_at", 0L),
        pending = j.optBoolean("pending", false),
      )
    }
  }
}

object PauseLogic {
  /** Seules actions encore permises en pause `all`. */
  val ALWAYS_ALLOWED = setOf("device_status")
  /** Actions refusees en pause `apps` : lire l'ecran, piloter une app. */
  val APPS_ACTIONS = setOf("screen_read", "ui_act")

  /** Durees proposees (secondes) ; 0 = jusqu'a la reprise. */
  val DURATIONS_S = listOf(15 * 60L, 60 * 60L, 0L)
  const val DEFAULT_PAUSE_S = 60 * 60L

  fun blocksAction(mode: String, action: String): Boolean = when (mode) {
    PauseMode.APPS -> action in APPS_ACTIONS
    PauseMode.ALL -> action !in ALWAYS_ALLOWED
    else -> false
  }

  /** Lire un ecran, toucher, saisir : y compris l'appui automatique sur « Envoyer » de WhatsApp. */
  fun blocksApps(mode: String): Boolean = mode == PauseMode.APPS || mode == PauseMode.ALL

  fun blocksEvents(mode: String): Boolean = mode == PauseMode.ALL

  /**
   * File hors ligne rejouee au welcome : en pause `all`, aucun `phone_event` ne doit partir (un appel manque mis de cote
   * avant la pause ne se rejoue pas a la reprise non plus). Rend (a garder, a purger) ; le reste de la file (actions
   * des boutons de notification) n'est pas un evenement du telephone et suit son cours.
   */
  fun splitOutbox(items: List<JSONObject>, mode: String): Pair<List<JSONObject>, List<JSONObject>> {
    if (!blocksEvents(mode)) return items to emptyList()
    return items.partition { it.optString("type") != "phone_event" }
  }

  /** Choix coherent : `off` n'a pas d'echeance, une echeance deja passee vaut `off`. */
  fun normalize(mode: String, untilS: Long, nowS: Long): Pair<String, Long> {
    if (!PauseMode.valid(mode) || mode == PauseMode.OFF) return PauseMode.OFF to 0L
    if (untilS > 0L && untilS <= nowS) return PauseMode.OFF to 0L
    return mode to untilS.coerceAtLeast(0L)
  }

  fun refusal(mode: String, until: Long): ActionError {
    val scope = if (mode == PauseMode.ALL) "toute action sur le téléphone" else "la lecture et le contrôle d'apps"
    val end = if (until > 0L) " jusqu'à ${hhmm(until)}" else " jusqu'à la reprise"
    return ActionError("paused", "Aura est en pause ($scope refusé)$end : l'utilisateur doit la reprendre (Réglages → Pause d'Aura)")
  }

  /** `welcome` du bridge : l'etat de pause peut etre un objet (`pause` / `pause_state`) ou des champs a plat. */
  fun fromWelcome(msg: JSONObject): PauseState? {
    msg.optJSONObject("pause")?.let { return fromMessage(it) }
    msg.optJSONObject("pause_state")?.let { return fromMessage(it) }
    if (msg.has("pause_mode")) return fromMessage(JSONObject().put("mode", msg.opt("pause_mode")).put("until", msg.opt("pause_until"))
      .put("by", msg.opt("pause_by")))
    return null
  }

  /** `pause_state {mode, until, by}` ; null si `mode` est absent ou inconnu (on ne devine pas un arret d'urgence). */
  fun fromMessage(msg: JSONObject): PauseState? {
    val mode = msg.optString("mode", "")
    if (!PauseMode.valid(mode)) return null
    val until = if (msg.isNull("until")) 0L else msg.optLong("until", 0L).coerceAtLeast(0L)
    val by = if (msg.isNull("by")) "" else msg.optString("by", "").take(40)
    return PauseState(mode = mode, until = until, by = by)
  }

  /** Estampille (ms) d'un etat distant s'il en porte une (`ts` ou `changed_at`, secondes ou ms), sinon null. */
  fun remoteStampMs(msg: JSONObject): Long? {
    for (k in listOf("changed_at", "ts")) {
      if (!msg.has(k) || msg.isNull(k)) continue
      val v = msg.optDouble(k, 0.0)
      if (v <= 0.0) continue
      return if (v > 1e11) v.toLong() else (v * 1000).toLong()
    }
    return null
  }

  enum class Decision { ADOPT_REMOTE, PUSH_LOCAL }

  /**
   * Au welcome : que faire de l'etat du bridge ? Un changement fait ICI hors ligne (`pending`) gagne, sauf si le
   * bridge prouve (estampille) qu'il a change plus tard. Sans changement local en attente, le bridge fait foi.
   */
  fun reconcile(local: PauseState, remoteStampMs: Long?): Decision {
    if (!local.pending) return Decision.ADOPT_REMOTE
    if (remoteStampMs != null && remoteStampMs > local.changedAt) return Decision.ADOPT_REMOTE
    return Decision.PUSH_LOCAL
  }

  /** Deux etats disent la meme chose (mode et echeance, `by` ignore) ? */
  fun sameAs(a: PauseState, b: PauseState, nowS: Long): Boolean {
    val ea = a.effectiveMode(nowS)
    val eb = b.effectiveMode(nowS)
    if (ea != eb) return false
    return ea == PauseMode.OFF || a.until == b.until
  }

  fun hhmm(epochS: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochSecond(epochS).atZone(zone))

  fun modeLabel(mode: String): String = when (mode) {
    PauseMode.ALL -> "tout"
    PauseMode.APPS -> "apps"
    else -> "normal"
  }

  /** Phrase d'etat (carte, notification, montre) : « En pause (apps) jusqu'à 15:42 ». */
  fun describe(st: PauseState, nowS: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val eff = st.effectiveMode(nowS)
    if (eff == PauseMode.OFF) return "Aura travaille normalement"
    val what = if (eff == PauseMode.ALL) "En pause totale" else "En pause (apps)"
    val end = if (st.until > 0L) " jusqu'à ${hhmm(st.until, zone)}" else " jusqu'à la reprise"
    return what + end
  }
}
