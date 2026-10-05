package dev.aura.mobile.device

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Appels manqués regroupés (`missed_calls_card`, PROTOCOL.md §11.10). Logique pure, testée en JVM (MissedLogicTest) ;
 * MissedCalls.kt en est la colle (notification par numéro, composeur, montre).
 *
 * Le contenu est une donnée de tiers : un nom vient d'un annuaire ou d'un correspondant. Texte seul, jamais un lien ni
 * une consigne ; le NUMÉRO n'entre dans un URI `tel:` qu'après une regex stricte.
 */
class MissedGroup(
  val number: String,
  val name: String?,
  val count: Int,
  /** epoch secondes du dernier manqué, 0 si inconnu */
  val lastTs: Long,
) {
  /** Numéro sûr pour `tel:` : le bouton « Rappeler » n'existe que pour lui. */
  val dialable: Boolean get() = MissedLogic.dialable(number)
}

/** reason : recap | burst | list (réponse à `missed_list`) ; paused : mode de la Pause d'Aura quand elle est active. */
class MissedCard(val groups: List<MissedGroup>, val paused: String?, val reason: String)

object MissedLogic {
  const val MAX_GROUPS = 10
  const val NAME_MAX = 60

  /** E.164 (le bridge normalise) : un « + » facultatif puis 6 à 15 chiffres. Rien d'autre ne fabrique un `tel:`. */
  private val TEL = Regex("^\\+?[0-9]{6,15}$")

  fun dialable(number: String): Boolean = TEL.matches(number)

  /** `{groups: [...], ts, reason?, paused?}` -> carte nettoyée ; null si `groups` manque (ce n'est pas une carte). Groupes vides permis. */
  fun parse(msg: JSONObject): MissedCard? {
    val arr = msg.optJSONArray("groups") ?: return null
    val groups = ArrayList<MissedGroup>()
    for (i in 0 until minOf(arr.length(), MAX_GROUPS)) {
      val g = arr.optJSONObject(i) ?: continue
      val number = (if (g.isNull("number")) "" else g.optString("number")).trim()
      val name = (if (g.isNull("name")) null else g.optString("name"))?.let { CallCardLogic.clean(it, NAME_MAX) }?.takeIf { it.isNotEmpty() }
      // ni numéro valide ni nom : rien à afficher ; un numéro illisible avec un nom s'affiche, sans bouton
      val usable = dialable(number)
      if (!usable && name == null) continue
      val count = g.optInt("count", 1).coerceIn(1, 999)
      val last = g.optDouble("last_ts", 0.0).let { if (it.isNaN() || it < 0) 0L else it.toLong() }
      groups += MissedGroup(if (usable) number else "", name, count, last)
    }
    val paused = (if (msg.isNull("paused")) null else msg.optString("paused")).takeIf { it == "apps" || it == "all" }
    val reason = msg.optString("reason").takeIf { it == "recap" || it == "burst" || it == "list" } ?: "recap"
    // un groupe sans numéro exploitable n'a pas de clé de notification : il ne s'affiche que s'il a un nom
    return MissedCard(groups.distinctBy { it.number.ifEmpty { "n:" + it.name } }, paused, reason)
  }

  /** « 1 appel manqué » / « 5 appels manqués ». */
  fun callsLabel(count: Int): String = if (count <= 1) "1 appel manqué" else "$count appels manqués"

  /** Nom, sinon numéro lisible. */
  fun who(g: MissedGroup): String = g.name ?: pretty(g.number)

  /** Titre de la notification : « 3 appels manqués : ACME Test ». */
  fun title(g: MissedGroup): String = "${callsLabel(g.count)} : ${who(g)}"

  /** Numéro français lisible (+33 1 99 00 11 22) ; tout autre numéro tel quel. */
  fun pretty(number: String): String {
    if (number.isEmpty()) return "numéro inconnu"
    val m = Regex("^\\+33([1-9])([0-9]{8})$").matchEntire(number) ?: return number
    val d = m.groupValues[1] + m.groupValues[2]
    return "+33 " + d[0] + " " + d.substring(1).chunked(2).joinToString(" ")
  }

  private val HM = DateTimeFormatter.ofPattern("HH:mm")

  /** « Dernier à 14:03 » (fuseau du téléphone) ; vide si l'heure est inconnue. */
  fun lastText(g: MissedGroup, zone: ZoneId = ZoneId.systemDefault()): String =
    if (g.lastTs <= 0) "" else "Dernier à " + HM.format(Instant.ofEpochSecond(g.lastTs).atZone(zone))

  /** Clé de notification d'un groupe. */
  fun key(g: MissedGroup): String = g.number.ifEmpty { "nom:" + (g.name ?: "?").lowercase() }

  /**
   * Ce qu'il faut faire des notifications déjà affichées (clé -> nombre d'appels qu'elles annonçaient) :
   *  - `post` : les groupes à afficher ou mettre à jour ; `alert` = avec heads-up (nouveau numéro, ou plus d'appels) ;
   *  - `cancel` : les notifications dont le groupe n'est plus dans la carte (numéro rappelé : le bridge renvoie la carte sans lui).
   * `list` (réponse à notre `missed_list`) ne ressuscite jamais une notification balayée et ne fait jamais de bruit :
   * elle met à jour ce qui est là et retire ce qui est fini.
   */
  class Plan(val post: List<Post>, val cancel: Set<String>) {
    class Post(val group: MissedGroup, val alert: Boolean)
  }

  fun plan(posted: Map<String, Int>, card: MissedCard): Plan {
    val keep = card.groups.map { key(it) }.toSet()
    val cancel = posted.keys.filter { it !in keep }.toSet()
    val post = ArrayList<Plan.Post>()
    for (g in card.groups) {
      val before = posted[key(g)]
      if (card.reason == "list") {
        if (before != null) post += Plan.Post(g, alert = false)
      } else {
        post += Plan.Post(g, alert = before == null || g.count > before)
      }
    }
    return Plan(post, cancel)
  }

  /** Pause totale (carte ou état local) : la notification s'affiche quand même (Aura te dit quelque chose, elle n'agit pas), mais sans heads-up. */
  fun quiet(card: MissedCard, localMode: String): Boolean = card.paused == "all" || localMode == PauseMode.ALL

  /** Nombre de numéros à rappeler (ce que compte la complication de la montre). */
  fun callbacks(card: MissedCard): Int = card.groups.size
}
