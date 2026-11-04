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

  /** show : dans l'ordre d'affichage (la plus recente en dernier) ; silent = sans son (plus de 30 min, `silent`, nuit calme). */
  class Result(val show: List<JSONObject>, val silent: List<Boolean>, val lastTs: Double)

  /**
   * lastTs null = premiere connexion : on memorise le ts max, rien a afficher.
   * `nightCalm` : le welcome du bridge dit que la nuit calme (23 h - 7 h) est en cours : le rattrapage ne sonne alors que pour
   * une VRAIE urgence (gravite 3 hors fin de tache), comme les notifications en direct.
   */
  fun select(recent: JSONArray?, lastTs: Double?, shown: Set<String>, nowS: Double, nightCalm: Boolean = false): Result {
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
    return Result(picked, picked.map { nowS - it.optDouble("ts", 0.0) > QUIET_AFTER_S || NotifRules.mutedByNight(it, nightCalm) },
      maxOf(lastTs, maxTs))
  }
}

/**
 * Regles de presentation d'un message `notify` du bridge (17/10 soir, « nuit calme », lot de fins de tache) : pures, testees en JVM.
 * Contrat serveur <-> natif (docs/PROTOCOL.md §11.14) : `silent`, `replaces`, `lot_update`, `fin_lot`, `held_sev`, `night_calm`.
 */
object NotifRules {
  const val CAT_FIN = "aura-fin"

  /** Sonne et s'affiche en tete d'ecran ? Gravite >= 2, sauf `silent` (nuit calme, mise a jour d'un lot) : un client d'avant
   *  ignore `silent`, mais le bridge livre alors aussi la gravite 1 : meme resultat. */
  fun isAlert(severity: Int, silent: Boolean): Boolean = severity >= 2 && !silent

  /** Rattrapage pendant la nuit calme : se tait sauf pour une urgence (3) qui n'est pas une fin de tache. */
  fun mutedByNight(n: JSONObject, nightCalm: Boolean): Boolean =
    n.optBoolean("silent", false) ||
      (nightCalm && (n.optInt("severity", 1) < 3 || n.optString("category") == CAT_FIN))

  /** Canal Android d'une notification du bridge : « Fins de tache » pour `aura-fin`, sinon Alertes / Infos. */
  fun channel(category: String, alert: Boolean, finChannel: String, alertChannel: String, infoChannel: String): String = when {
    category == CAT_FIN -> finChannel
    alert -> alertChannel
    else -> infoChannel
  }

  /** Ids des notifications qu'un lot remplace (`replaces`) : seulement des ids bien formes, 50 au plus. */
  fun replacedIds(msg: JSONObject, idRe: Regex): List<String> {
    val a = msg.optJSONArray("replaces") ?: return emptyList()
    return (0 until minOf(a.length(), 50)).mapNotNull { a.optString(it).takeIf { id -> idRe.matches(id) } }
  }

  // memes libelles que NOTIF_LABELS de la PWA. 17/10 : une notification sur sept (missions, superviseur, rapport)
  // tombait sur le titre generique « Aura » ; les categories courantes du serveur sont ici, les autres passent par label()
  private val LABELS = mapOf(
    "media" to "Média", "night" to "Nuit", "aura-agenda" to "Agenda", "aura-sante" to "Santé",
    "aura-rappel" to "Rappel", "memoire" to "Santé", "aura-conseil" to "Conseil", "aura" to "Aura",
    "notifications" to "AURA", "aura-appel" to "Appel", "aura-mail" to "Mail",
    "aura-fin" to "Tâche terminée", "aura-temps" to "Temps",
    "aura-missions" to "Mission", "aura-superviseur" to "Superviseur", "aura-rapport" to "Rapport",
    "aura-telephone" to "Téléphone",
    "alerte" to "Alerte", "info" to "Info", "sante" to "Santé",
  )

  /** Libelle d'une categorie ; une `aura-xxx` inconnue devient « Xxx » (jamais le titre generique pour une categorie neuve). */
  fun label(category: String): String =
    LABELS[category] ?: category.removePrefix("aura-").takeIf { category.startsWith("aura-") && it.isNotBlank() }
      ?.replace('-', ' ')?.replaceFirstChar { it.uppercase() } ?: "Aura"
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
