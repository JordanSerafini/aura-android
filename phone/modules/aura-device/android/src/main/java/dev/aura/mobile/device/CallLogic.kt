package dev.aura.mobile.device

import org.json.JSONObject

/*
 * Appel entrant : detection de la sonnerie et carte `call_card` renvoyee par le bridge. Logique pure, testee en JVM
 * (CallLogicTest) ; PhoneEvents.kt (ecoute) et CallCards.kt (notification, montre) en sont la colle Android.
 */

/**
 * Suit une sonnerie a partir des diffusions PHONE_STATE. Android en envoie DEUX par appel entrant : une premiere sans
 * numero, puis une avec (si READ_CALL_LOG est accorde). On emet des que le numero arrive ; sinon, apres un court delai
 * de grace (appelant masque, ou numero illisible). Une seule emission par sonnerie.
 */
class RingTracker {
  enum class Decision { NONE, EMIT, WAIT }

  companion object {
    const val GRACE_MS = 1500L
    const val RINGING = "RINGING"
  }

  private var ringing = false
  private var emitted = false
  var number: String? = null
    private set

  /** state : EXTRA_STATE de la diffusion (RINGING, OFFHOOK, IDLE). */
  fun onState(state: String?, incoming: String?): Decision {
    if (state != RINGING) {
      ringing = false
      emitted = false
      number = null
      return Decision.NONE
    }
    val first = !ringing
    ringing = true
    val n = incoming?.trim()?.takeIf { it.isNotEmpty() }
    if (n != null) number = n
    if (emitted) return Decision.NONE
    if (n != null) {
      emitted = true
      return Decision.EMIT
    }
    return if (first) Decision.WAIT else Decision.NONE
  }

  /** Delai de grace ecoule sans numero : emettre quand meme (numero masque). */
  fun onGraceElapsed(): Decision {
    if (!ringing || emitted) return Decision.NONE
    emitted = true
    return Decision.EMIT
  }
}

/**
 * Carte d'appel : donnee EXTERNE (fiche client), affichee en texte seul. Jamais une consigne, jamais un lien.
 * `partial` : le bridge n'a pas pu interroger toutes ses sources (base des tickets muette) ; l'absence d'une ligne
 * ne prouve alors rien (PROTOCOL.md §10.2).
 */
class CallCard(val number: String?, val title: String, val lines: List<String>, val ts: Long, val partial: Boolean = false)

object CallCardLogic {
  const val MAX_LINES = 5
  const val LINE_MAX = 140
  const val TITLE_MAX = 60
  const val DEFAULT_TITLE = "Numéro connu"
  /** Ajoute a la notification quand le bridge dit `partial` : « pas de ticket » ne doit pas se lire comme « aucun ticket ». */
  const val PARTIAL_NOTE = "Fiche partielle : tickets et historique non vérifiés"
  /** Une carte de plus de 3 min n'a plus de sens : l'appel est fini ou decroche. */
  const val MAX_AGE_S = 180L

  // controles, separateurs de ligne et marques bidirectionnelles (qui permettent de maquiller un texte)
  private val CONTROL = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u2028\\u2029\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]+")
  private val PHONE = Regex("^\\+?[0-9][0-9 .\\-()]{3,24}$")

  fun clean(s: String?, max: Int): String {
    val one = (s ?: "").replace(CONTROL, " ").trim().replace(Regex("\\s+"), " ")
    return if (one.length <= max) one else one.take(max - 1).trimEnd() + "…"
  }

  /**
   * `{type:"call_card", number, title, lines:[...], ts}` -> carte nettoyee, ou null s'il n'y a rien a afficher.
   * 3 a 5 lignes au plus ; `title` absent = « Numéro connu » ; le numero n'est gardé que s'il a la forme d'un numero.
   */
  fun parse(msg: JSONObject): CallCard? {
    val arr = msg.optJSONArray("lines")
    val lines = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
      val v = arr?.opt(i)
      if (v is String) clean(v, LINE_MAX).takeIf { it.isNotEmpty() } else null
    }.take(MAX_LINES)
    val title = clean(if (msg.isNull("title")) null else msg.optString("title"), TITLE_MAX).ifEmpty { DEFAULT_TITLE }
    val number = (if (msg.isNull("number")) null else msg.optString("number"))?.trim()?.takeIf { PHONE.matches(it) }
    if (lines.isEmpty() && msg.isNull("title")) return null
    val ts = if (msg.isNull("ts")) 0L else msg.optLong("ts", 0L)
    val partial = !msg.isNull("partial") && msg.opt("partial") == true  // booleen strict : « false » ou 0 ne declenchent rien
    return CallCard(number, title, lines, ts, partial)
  }

  /** ts (epoch s, ms toleres) absent = recent ; trop vieux ou dans le futur lointain = a ignorer. */
  fun fresh(ts: Long, nowS: Long): Boolean {
    if (ts <= 0L) return true
    val s = if (ts > 100_000_000_000L) ts / 1000 else ts
    return s >= nowS - MAX_AGE_S && s <= nowS + 600
  }

  /** Texte de la notification : les lignes, une par ligne, puis la mention « fiche partielle » si une source manque. */
  fun body(card: CallCard): String = (card.lines + if (card.partial) listOf(PARTIAL_NOTE) else emptyList()).joinToString("\n")

  /** Ligne repliee de la notification : la 1re ligne, precedee de « Fiche partielle · » si une source manque. */
  fun summary(card: CallCard): String {
    val first = card.lines.firstOrNull() ?: "Appel entrant"
    return if (card.partial) "Fiche partielle · $first" else first
  }

  /** Forme envoyee a la montre (/aura/call_card). */
  fun toWatch(card: CallCard): JSONObject = JSONObject().put("title", card.title)
    .put("lines", org.json.JSONArray(card.lines)).put("ts", card.ts)
    .apply {
      if (card.number != null) put("number", card.number)
      if (card.partial) put("partial", true)
    }
}
