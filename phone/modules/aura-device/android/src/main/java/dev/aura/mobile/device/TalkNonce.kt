package dev.aura.mobile.device

import java.security.SecureRandom

/*
 * Jeton a usage unique de `aura://talk?autostart=1&n=<jeton>` (revue securite du 17/10, constat JS n°2). Le schema `aura`
 * est ouvrable par n'importe quelle app ou page web : sans jeton, un lien suffisait a ouvrir le micro sans geste.
 * Seule la tuile « Dicter à Aura » (DictateTileService, meme processus) EMET un jeton, juste avant de lancer le lien ;
 * le JS le consomme (AuraDevice.consumeTalkNonce) avant de demarrer l'ecoute. Un jeton est valable 30 s, une seule
 * fois ; aucun jeton valide = l'ecran Talk s'ouvre mais l'utilisateur doit toucher pour parler. En memoire seulement
 * (pas de persistance : un jeton ne doit pas survivre au processus). Logique pure, testee en JVM (TalkNonceTest).
 */
class TalkNonce(private val ttlMs: Long = TTL_MS, private val random: () -> ByteArray = ::secureBytes) {
  private var current: String? = null
  private var expires = 0L

  /** Nouveau jeton (remplace le precedent). 128 bits aleatoires, hexadecimal. */
  @Synchronized fun issue(now: Long): String {
    val t = random().joinToString("") { "%02x".format(it) }
    current = t
    expires = now + ttlMs
    return t
  }

  /** true une seule fois, avant expiration, pour le jeton emis ; toute autre valeur (ou un second appel) : false. */
  @Synchronized fun consume(candidate: String?, now: Long): Boolean {
    val cur = current ?: return false
    if (now > expires) {
      current = null
      return false
    }
    val ok = candidate != null && constantTimeEquals(candidate, cur)
    if (ok) current = null  // usage unique ; un essai rate ne brule pas le jeton (128 bits : aucune devinette possible)
    return ok
  }

  private fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var d = 0
    for (i in a.indices) d = d or (a[i].code xor b[i].code)
    return d == 0
  }

  companion object {
    const val TTL_MS = 30_000L
    private val rng = SecureRandom()
    private fun secureBytes(): ByteArray = ByteArray(16).also { rng.nextBytes(it) }
  }
}
