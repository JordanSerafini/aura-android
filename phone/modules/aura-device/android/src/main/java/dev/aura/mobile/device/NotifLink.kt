package dev.aura.mobile.device

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Liens des boutons des notifications Android d'Aura (aura://notif/<id>/act|ask), revue securite du 27/10
 * (F1). Le schema aura:// s'ouvre depuis n'importe quelle app ou page web, et `act` EXECUTE l'action de la notification
 * dans la PWA (demande a Aura, rappel). Le lien porte donc un sceau `k` (HMAC-SHA256 tronque a 128 bits) calcule avec une
 * cle propre a cette installation (Notifs.linkKey, prefs, allowBackup=false) : App.tsx ne relaie a la PWA qu'un lien
 * scelle, un lien d'ailleurs ouvre seulement la liste des notifications. Logique pure, testee en JVM (NotifLinkTest).
 */
object NotifLink {
  private val SEALED = Regex("^aura://notif/([A-Za-z0-9_-]{1,64})/(act|ask)\\?k=([0-9a-f]{32})$")
  private val ID = Regex("[A-Za-z0-9_-]{1,64}")

  fun seal(key: ByteArray, id: String, kind: String): String {
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
    return mac.doFinal("notif/$id/$kind".toByteArray()).take(16).joinToString("") { "%02x".format(it) }
  }

  /** Lien scelle ; un id hors forme (que App.tsx ne relaierait pas) garde le lien nu d'avant. */
  fun uri(key: ByteArray, id: String, kind: String): String =
    if (ID.matches(id)) "aura://notif/$id/$kind?k=${seal(key, id, kind)}" else "aura://notif/$id/$kind"

  fun verify(key: ByteArray, url: String?): Boolean {
    val m = SEALED.matchEntire(url ?: return false) ?: return false
    val (id, kind, k) = m.destructured
    return MessageDigest.isEqual(seal(key, id, kind).toByteArray(), k.toByteArray())
  }
}
