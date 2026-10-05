package dev.aura.mobile.protocol

import kotlinx.serialization.Serializable

/**
 * `/aura/call_card` : fiche d'appel poussee par le telephone (le bridge l'a calculee a la sonnerie). Donnee EXTERNE :
 * affichee en texte seul, jamais interpretee, aucun lien. Le telephone a deja nettoye ; la montre le refait (defense
 * en profondeur) et garde 5 lignes au plus. Rien n'est ecrit sur disque au-dela de la notification.
 */
@Serializable
data class CallCard(val title: String = "", val lines: List<String> = emptyList(), val number: String? = null, val ts: Long = 0L,
                    /** Le bridge n'a pas pu interroger toutes ses sources : l'absence d'une ligne ne prouve rien. */
                    val partial: Boolean = false)

object CallCardLogic {
  const val MAX_LINES = 5
  const val LINE_MAX = 140
  const val TITLE_MAX = 60
  const val DEFAULT_TITLE = "Numéro connu"
  const val MAX_AGE_S = 180L
  const val PARTIAL_NOTE = "Fiche partielle : tickets et historique non vérifiés"

  private val CONTROL = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u2028\\u2029\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]+")

  fun clean(s: String?, max: Int): String {
    val one = (s ?: "").replace(CONTROL, " ").trim().replace(Regex("\\s+"), " ")
    return if (one.length <= max) one else one.take(max - 1).trimEnd() + "…"
  }

  /** Carte nettoyee, ou null s'il n'y a rien a afficher ou si elle est perimee (l'appel est fini). */
  fun prepare(card: CallCard?, nowS: Long): CallCard? {
    if (card == null) return null
    val lines = card.lines.map { clean(it, LINE_MAX) }.filter { it.isNotEmpty() }.take(MAX_LINES)
    val title = clean(card.title, TITLE_MAX).ifEmpty { DEFAULT_TITLE }
    if (lines.isEmpty() && card.title.isBlank()) return null
    if (card.ts > 0L) {
      val s = if (card.ts > 100_000_000_000L) card.ts / 1000 else card.ts
      if (s < nowS - MAX_AGE_S || s > nowS + 600) return null
    }
    return card.copy(title = title, lines = lines, number = card.number?.let { clean(it, 30) }?.takeIf { it.isNotEmpty() })
  }

  /** Corps de la notification : les lignes, puis la mention « fiche partielle » si une source manque. */
  fun body(card: CallCard): String = (card.lines + if (card.partial) listOf(PARTIAL_NOTE) else emptyList()).joinToString("\n")

  /** Ligne repliee : la 1re ligne, precedee de « Fiche partielle · » si une source manque. */
  fun summary(card: CallCard): String {
    val first = card.lines.firstOrNull() ?: "Appel entrant"
    return if (card.partial) "Fiche partielle · $first" else first
  }
}
