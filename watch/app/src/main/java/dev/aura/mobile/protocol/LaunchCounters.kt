package dev.aura.mobile.protocol

/**
 * Compteur `/aura/usage` d'un lancement de l'app (PROTOCOL.md §3, §21) : des noms et des nombres, jamais un contenu.
 * Le téléphone les range sous `montre.<nom>` (UsageLogic.watchCounters, nom `[a-z0-9_]{1,20}`, valeur 1..100).
 * 0.2.0 : la complication ouvre aussi la demande en attente ; elle porte sa source pour ne pas gonfler `montre.tuile`.
 */
object LaunchCounters {
  /** Extra d'intent : d'où vient le lancement (`tuile`, `complication`). */
  const val EXTRA_SOURCE = "source"
  const val TILE = "tuile"
  const val COMPLICATION = "complication"

  /** Nom du compteur à envoyer pour ce lancement, null = rien à compter (lanceur, notification, recréation). */
  fun counter(record: Boolean, openConfirm: Boolean, source: String?): String? = when (source) {
    TILE -> TILE
    COMPLICATION -> COMPLICATION
    // tuile rendue avant 0.2.0 (sans source) : ses deux boutons étaient les seuls à porter ces extras
    null -> if (record || openConfirm) TILE else null
    else -> null
  }

  /** `{"counters":{"<nom>":1}}`. */
  fun payload(counter: String): ByteArray = """{"counters":{"$counter":1}}""".toByteArray(Charsets.UTF_8)
}
