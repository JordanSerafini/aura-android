package dev.aura.mobile.device

/**
 * Copie, partage et indice de presse-papiers demandes par l'interface web (PROTOCOL.md §32), partie pure (testee sans Android).
 *
 * « Ce que j'ai copié » : a l'ouverture de l'app, la PWA propose « Demander à Aura ? » si un TEXTE a ete copie AILLEURS
 * depuis moins de 15 min et pas encore propose. On ne lit que la description du presse-papiers (type, libelle, date) :
 * Android 12+ n'affiche son avis « Aura a collé depuis le presse-papiers » qu'a la lecture du contenu, faite seulement
 * quand l'utilisateur appuie sur « Demander ». Nos propres copies portent le libelle « Aura » : jamais proposees.
 */
object ClipLogic {
  const val OWN_LABEL = "Aura"
  const val MAX_AGE_MS = 15 * 60_000L
  const val TEXT_MAX = 15_000
  const val PREF_SEEN = "clip_seen_stamp"

  /** Age en secondes a annoncer, ou null (rien a proposer). `stamp` = ClipDescription.timestamp (currentTimeMillis). */
  fun hintAge(stamp: Long, seen: Long, now: Long, isText: Boolean, label: String?): Long? {
    if (!isText || stamp <= 0L || stamp <= seen || label == OWN_LABEL) return null
    val age = now - stamp
    return if (age in 0..MAX_AGE_MS) age / 1000 else null
  }

  /** Repere a memoriser apres un examen : le presse-papiers vu ne sera plus propose (meme s'il n'est pas retenu). */
  fun nextSeen(stamp: Long, seen: Long): Long = maxOf(stamp, seen)

  /** Borne d'une copie demandee par la page (une reponse longue se copie, au-dela on coupe). */
  const val COPY_MAX = 100_000

  /**
   * `copy_text` de la page web : memes planchers locaux que `clipboard_set` (revue securite du 27/10, N8). La page est le
   * code du serveur : elle ne pose pas un IBAN, une carte ou une adresse crypto sans la carte d'accord, et rien pendant
   * une pause « Tout ». Rend la raison du refus, ou null si la copie peut se faire.
   */
  fun copyRefusal(text: String, pauseMode: String): String? = when {
    PauseLogic.blocksAction(pauseMode, "clipboard_set") -> "pause"
    ClipGuard.payment(text) -> "paiement"
    else -> null
  }

  /** `clip_read` de la page web : refuse pendant une pause qui bloque `clipboard_get`, comme l'action du serveur. */
  fun readAllowed(pauseMode: String): Boolean = !PauseLogic.blocksAction(pauseMode, "clipboard_get")
}
