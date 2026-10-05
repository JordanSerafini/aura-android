package dev.aura.mobile.device

import org.json.JSONArray
import org.json.JSONObject

/*
 * Logique pure (sans Android) de BridgeClient, Notifs et WatchConvs : testee en JVM (BridgeLogicTest).
 */

/**
 * Closes 4401 successifs sans welcome entre eux. Le bridge ferme en 4401 pour un jeton refuse, mais aussi
 * pour un hello arrive apres 10 s ou illisible (server.py, ws_handler) : un seul 4401 ne prouve rien.
 * Jusqu'au 28/09, le premier suffisait a passer « rejected » pour toujours (plus aucune reconnexion).
 */
class BadTokenCounter(private val max: Int = MAX) {
  companion object {
    const val MAX = 3
  }

  @Volatile var count = 0
    private set

  /** true = abandon (« rejected ») ; false = backoff normal. */
  fun onClose(code: Int): Boolean {
    if (code != BridgeClient.CLOSE_BAD_TOKEN) return false
    count++
    return count >= max
  }

  fun reset() {
    count = 0
  }
}

/**
 * Rattrapage des notifications au welcome (`recent` du bridge) : ce qui est arrive pendant que la WebSocket
 * etait coupee n'apparaissait nulle part sur le telephone (ni notify, ni Web Push cote natif).
 */
object NotifCatchUp {
  const val MAX = 8
  const val QUIET_AFTER_S = 30 * 60.0

  /** show : dans l'ordre d'affichage (la plus recente en dernier) ; silent = sans son (plus de 30 min). */
  class Result(val show: List<JSONObject>, val silent: List<Boolean>, val lastTs: Double)

  /** lastTs null = premiere connexion : on memorise le ts max, rien a afficher. */
  fun select(recent: JSONArray?, lastTs: Double?, shown: Set<String>, nowS: Double): Result {
    val all = (0 until (recent?.length() ?: 0)).mapNotNull { recent?.optJSONObject(it) }
    val maxTs = all.maxOfOrNull { it.optDouble("ts", 0.0) } ?: 0.0
    if (lastTs == null) return Result(emptyList(), emptyList(), if (maxTs > 0) maxTs else nowS)
    val picked = all
      .filter {
        val ts = it.optDouble("ts", 0.0)
        ts > lastTs && !it.optBoolean("read") && !it.optBoolean("archived") && !it.optBoolean("quiet") &&
          it.optString("text").isNotBlank() && it.optString("id") !in shown
      }
      .sortedByDescending { it.optDouble("ts", 0.0) }
      .take(MAX)
      .reversed()
    return Result(picked, picked.map { nowS - it.optDouble("ts", 0.0) > QUIET_AFTER_S }, maxOf(lastTs, maxTs))
  }
}

/** Numero a rappeler d'une carte d'appel (apres_appel.py card()) : ligne « 📱 <numero> », sinon fin de la 1re ligne. */
fun callNumber(text: String): String? {
  val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
  if (lines.isEmpty()) return null
  lines.firstOrNull { it.startsWith("📱") }?.let { return phoneOf(it.removePrefix("📱")) }
  val first = lines[0]
  val cut = first.lastIndexOf(" — ")
  if (cut < 0) return null
  return phoneOf(first.substring(cut + 3))
}

private val PHONE_RE = Regex("^\\+?[0-9][0-9 .\\-()]{3,24}$")

private fun phoneOf(raw: String): String? {
  val s = raw.trim()
  if (!PHONE_RE.matches(s)) return null
  val digits = s.filterIndexed { i, c -> c.isDigit() || (c == '+' && i == 0) }
  return digits.takeIf { it.count(Char::isDigit) >= 3 }
}

/**
 * Onglet neuf demande par la montre (`/aura/conv_new`) : jusqu'a son arrivee (`conv` portant le meme `req`,
 * 15 s au plus), les messages de la montre sont retenus, puis envoyes dans cet onglet ; a l'expiration, sans
 * `conv`. Sans cela, une phrase dite juste apres « Nouvelle conversation » partait dans l'ancien onglet.
 */
class NewConvWait<T>(private val waitMs: Long = WAIT_MS) {
  companion object {
    const val WAIT_MS = 15_000L
  }

  class Pending(val req: String, val node: String, val at: Long)
  class Resolved<T>(val node: String, val held: List<T>)

  private var pending: Pending? = null
  private val held = mutableListOf<T>()

  @Synchronized fun start(req: String, node: String, now: Long) {
    pending = Pending(req, node, now)  // une 2e demande remplace la 1re ; les messages retenus suivent
  }

  @Synchronized fun waiting(now: Long): Boolean = pending?.let { now - it.at < waitMs } == true

  /** true = retenu (a envoyer plus tard par resolve/expire/cancel). */
  @Synchronized fun hold(item: T, now: Long): Boolean {
    if (!waiting(now)) return false
    held += item
    return true
  }

  /** Ce `req` est celui attendu (sans rien consommer). */
  @Synchronized fun matches(req: String, now: Long): Boolean = pending?.let { it.req == req && now - it.at < waitMs } == true

  /** `conv` recu avec ce `req` : le noeud qui l'attend et les messages retenus ; null si ce n'est pas le notre. */
  @Synchronized fun resolve(req: String, now: Long): Resolved<T>? {
    val p = pending ?: return null
    if (p.req != req || now - p.at >= waitMs) return null
    pending = null
    return Resolved(p.node, drain())
  }

  /** Delai ecoule : messages retenus a envoyer sans `conv` (null s'il n'y a rien a faire). */
  @Synchronized fun expire(now: Long): List<T>? {
    val p = pending ?: return null
    if (now - p.at < waitMs) return null
    pending = null
    return drain()
  }

  /** Abandon (bridge injoignable) : messages retenus a envoyer sans `conv`. */
  @Synchronized fun cancel(): List<T> {
    pending = null
    return drain()
  }

  private fun drain(): List<T> = held.toList().also { held.clear() }
}

/** Au plus un envoi par `gapMs` : delai avant le prochain (0 = tout de suite). */
fun coalesceDelay(lastAt: Long, now: Long, gapMs: Long): Long = maxOf(0L, lastAt + gapMs - now)

object BridgeLogic {
  /** Messages client -> bridge ajoutes le 01/10 soir : un bridge plus ancien repond `error bad_type` qui les cite. */
  val FLUX_CLIENT_TYPES = listOf("missed_list", "time_list", "time_confirm", "usage_report", "limit_cancel", "viewing")

  /**
   * `notify` dont un message structure du meme envoi (missed_calls_card, time_proposals : §11.10, §11.11) porte deja la
   * notification native avec ses boutons : la notification texte du bridge ferait doublon. Les autres `aura-appel`
   * (fiche apres appel) et `aura-temps` n'ont pas ces mute_key et restent affichees.
   */
  fun coveredByCard(category: String, muteKey: String?): Boolean =
    (category == "aura-appel" && muteKey == "proactive:appel") || (category == "aura-temps" && muteKey == "proactive:temps")

  /** Le premier nom de `names` cite par un message `bad_type` du bridge, null si aucun. */
  fun citedType(message: String, names: Collection<String>): String? = names.firstOrNull { message.contains(it) }
}
