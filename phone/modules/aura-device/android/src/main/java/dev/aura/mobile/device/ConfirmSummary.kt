package dev.aura.mobile.device

import org.json.JSONObject

/*
 * Resume de confirmation CONSTRUIT LOCALEMENT depuis action + params (revue securite du 17/10, M3) : ce que
 * l'utilisateur approuve sur le telephone ou la montre est ce qui va s'executer, jamais le `summary` libre du bridge
 * (« Envoyer "ok" a Maman » pendant que params.to est un autre numero). Logique pure, testee en JVM (ConfirmSummaryTest).
 *
 * Le destinataire est resolu par l'appelant (Contacts.resolve : nom + numero reels) et passe en `resolve` ; sans
 * resolution (contact introuvable, ambigu, permission absente) le destinataire brut est affiche, marque « non résolu ».
 * Tout texte emprunte aux params est nettoye (controles, marques bidi, espaces) et tronque.
 */
object ConfirmSummary {
  const val TEXT_MAX = 120
  const val RECIPIENT_MAX = 60
  private const val GENERIC_PARAMS = 3
  private const val GENERIC_VALUE_MAX = 40

  /** Destinataire resolu : (numero, nom ou null). */
  fun interface Resolver { fun resolve(to: String): Pair<String, String?>? }

  // controles + marques d'orientation / isolats bidi (U+200E/F, U+202A-E, U+2066-9) : ils falsifient l'ordre de lecture
  private val BIDI = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")

  fun clean(s: String?, max: Int): String {
    val t = (s ?: "").replace(BIDI, "").map { if (Character.isISOControl(it)) ' ' else it }.joinToString("")
      .replace(Regex("\\s+"), " ").trim()
    return if (t.length > max) t.take(max - 1).trimEnd() + "…" else t
  }

  private fun quote(s: String?, max: Int = TEXT_MAX) = "« ${clean(s, max)} »"

  /** « Maman (+33612345678) », « +33612345678 », ou « « x » (non résolu) ». */
  fun recipient(to: String?, r: Resolver?): String {
    val raw = clean(to, RECIPIENT_MAX)
    if (raw.isEmpty()) return "destinataire absent"
    val hit = try { r?.resolve(to.orEmpty()) } catch (e: ActionError) { null }
    if (hit == null) return "« $raw » (non résolu)"
    val (number, name) = hit
    val n = clean(number, RECIPIENT_MAX)
    return if (name.isNullOrBlank()) n else "${clean(name, RECIPIENT_MAX)} ($n)"
  }

  fun build(action: String, p: JSONObject, r: Resolver? = null): String = when (action) {
    "sms_send" -> "Envoyer un SMS à ${recipient(p.str("to"), r)} : ${quote(p.str("text"))}"
    "call" -> "Appeler ${recipient(p.str("to"), r)}"
    "camera_snap" -> "Prendre une photo (caméra ${if (p.str("facing") == "front") "avant" else "arrière"})"
    "notif_reply" -> "Répondre à une notification : ${quote(p.str("text"))}"
    "message_send" -> "Envoyer un message ${clean(p.str("app"), 20)} à ${recipient(p.str("to"), r)} : ${quote(p.str("text"))}"
    "ui_act" -> uiAct(p)
    else -> generic(action, p)
  }

  private fun uiAct(p: JSONObject): String {
    val app = clean(p.str("app"), 40).ifEmpty { "une app" }
    return when (p.str("op")) {
      "tap" -> "Toucher ${quote(p.str("text") ?: p.str("desc"), 60)} dans $app"
      "type" -> "Saisir ${quote(p.str("text"))} dans $app"
      else -> "Contrôler $app (${clean(p.str("op"), 20)})"
    }
  }

  /** Action sans modele : son nom et ses premiers parametres REELS, jamais un libelle du bridge. */
  private fun generic(action: String, p: JSONObject): String {
    val parts = p.keys().asSequence().sorted().take(GENERIC_PARAMS).map { k ->
      "${clean(k, 20)} = ${clean(p.opt(k)?.toString(), GENERIC_VALUE_MAX)}"
    }.toList()
    val extra = if (p.length() > GENERIC_PARAMS) ", …" else ""
    return "Action « ${clean(action, 40)} »" + if (parts.isEmpty()) "" else " (${parts.joinToString(", ")}$extra)"
  }
}
