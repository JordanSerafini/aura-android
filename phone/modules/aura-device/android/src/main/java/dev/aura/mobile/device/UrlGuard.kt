package dev.aura.mobile.device

/*
 * `open_url` : http et https SEULEMENT (revue securite du 17/10, L1). Avant, Intent(ACTION_VIEW, Uri.parse(url)) recevait
 * n'importe quoi : file://, content://, market://, tel:, sms:, intent:, deep link d'une appli tierce, ouverts en arriere-plan
 * par Launcher.start sans aucune confirmation. Logique pure, testee en JVM (UrlGuardTest).
 */
object UrlGuard {
  const val MAX_LEN = 2000
  private val WEB = Regex("^https?://[^/?#\\s]+[^\\s\\u0000-\\u001f]*$", RegexOption.IGNORE_CASE)

  /** L'URL nettoyee si c'est une adresse http(s) avec un hote ; ActionError bad_params sinon (rien n'est lance). */
  fun webUrl(raw: String): String {
    val u = raw.trim()
    if (u.length > MAX_LEN || !WEB.matches(u)) {
      throw ActionError("bad_params", "open_url : seules les adresses http:// et https:// sont ouvertes")
    }
    return u
  }
}
