package dev.aura.mobile.device

/*
 * URL du bridge (revue securite du 17/10, L2) : le jeton appareil part en clair dans le `hello` d'un ws://. Un APK de
 * release n'accepte donc que wss:// ; ws:// n'est permis que dans un APK debuggable (chemin de dev explicite, cote JS :
 * AURA_ALLOW_INSECURE_DEV=1, build-config.js). Logique pure, testee en JVM (BridgeUrlTest).
 */
object BridgeUrl {
  private val WSS = Regex("^wss://[^/?#\\s]+(?:[/?#]\\S*)?$", RegexOption.IGNORE_CASE)
  private val WS = Regex("^wss?://[^/?#\\s]+(?:[/?#]\\S*)?$", RegexOption.IGNORE_CASE)

  fun accepted(url: String, debuggable: Boolean): Boolean = (if (debuggable) WS else WSS).matches(url.trim())

  /** L'URL a utiliser : celle de la config si elle est acceptee, sinon la valeur par defaut (jamais de connexion en clair). */
  fun effective(configured: String?, default: String, debuggable: Boolean): String =
    configured?.trim()?.takeIf { accepted(it, debuggable) } ?: default
}
