package dev.aura.mobile.device

import org.json.JSONArray
import org.json.JSONObject

/*
 * Temps non saisi en un geste (`time_proposals`, `time_confirm`, `time_result`, PROTOCOL.md §11.11). Logique pure,
 * testée en JVM (TimeLogicTest) ; TimeProposals.kt et TimeConfirmActivity.kt en sont la colle Android.
 *
 * `time_confirm` est la confirmation EXPLICITE de l'utilisateur : il ne part que de l'écran de confirmation natif, sur les
 * identifiants qu'il y a VUS, jamais depuis une notification seule. Tout texte (ticket, client, preuve) vient de bases
 * de tiers : affiché en texte seul, jamais interprété.
 */
class TimeItem(
  val id: String,
  val ticket: String,
  val title: String,
  val client: String,
  val minutes: Int,
  val evidence: String,
  val copyText: String,
)

class TimeFailure(val id: String, val reason: String, val copyText: String?)
class TimeResult(val ok: List<String>, val failed: List<TimeFailure>)

object TimeLogic {
  const val MAX_ITEMS = 15
  /** Plafond du bridge pour `time_confirm` (1 à 30 ids de 64 caractères). */
  const val CONFIRM_MAX = 30
  private val ID = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}-([A-Za-z0-9_-]{1,40})$")

  private fun text(o: JSONObject, key: String, max: Int): String =
    if (o.isNull(key)) "" else CallCardLogic.clean(o.opt(key)?.let { if (it is String) it else null }, max)

  /** `{items: [...], ts}` -> propositions valides (id bien formé, durée 1..480, sans doublon d'id), 15 au plus. */
  fun parseProposals(msg: JSONObject): List<TimeItem> {
    val arr = msg.optJSONArray("items") ?: return emptyList()
    val out = ArrayList<TimeItem>()
    val seen = HashSet<String>()
    for (i in 0 until arr.length()) {
      val o = arr.optJSONObject(i) ?: continue
      val id = if (o.isNull("id")) "" else (o.opt("id") as? String ?: "")
      val m = ID.matchEntire(id) ?: continue
      val minutes = (o.opt("minutes") as? Number)?.toInt() ?: 0  // un nombre, pas une chaine qui y ressemble
      if (minutes !in 1..480 || !seen.add(id)) continue
      out += TimeItem(id, m.groupValues[1], text(o, "title", 80), text(o, "client", 60), minutes, text(o, "evidence", 120),
        text(o, "copy_text", 300))
      if (out.size >= MAX_ITEMS) break
    }
    return out
  }

  fun total(items: List<TimeItem>): Int = items.sumOf { it.minutes }

  /** « 3 temps à valider (95 min) ». */
  fun summary(items: List<TimeItem>): String = "${items.size} temps à valider (${total(items)} min)"

  /** Les ids de l'écran de confirmation, dans l'ordre affiché, dans la limite du bridge. */
  fun confirmIds(items: List<TimeItem>): List<String> = items.map { it.id }.take(CONFIRM_MAX)

  /** Message client -> bridge. */
  fun confirmMessage(ids: List<String>): JSONObject = JSONObject().put("type", "time_confirm").put("ids", JSONArray(ids))

  /** `{ok: [ids], failed: [{id, reason, copy_text?}]}` ; null si ce n'est pas un résultat. */
  fun parseResult(msg: JSONObject): TimeResult? {
    if (!msg.has("ok") && !msg.has("failed")) return null
    val okArr = msg.optJSONArray("ok")
    val ok = (0 until (okArr?.length() ?: 0)).mapNotNull { okArr?.opt(it) as? String }.filter { it.length <= 64 }
    val failArr = msg.optJSONArray("failed")
    val failed = (0 until (failArr?.length() ?: 0)).mapNotNull { i ->
      val f = failArr?.optJSONObject(i) ?: return@mapNotNull null
      val id = (f.opt("id") as? String ?: "").take(64)
      val reason = text(f, "reason", 200).ifEmpty { "échec" }
      val copy = text(f, "copy_text", 300).takeIf { it.isNotEmpty() }
      TimeFailure(id, reason, copy)
    }
    return TimeResult(ok, failed)
  }

  /** Numéro de ticket d'un id `AAAA-MM-JJ-<ticket>`. */
  fun ticketOf(id: String): String? = ID.matchEntire(id)?.groupValues?.get(1)

  fun resultTitle(r: TimeResult): String = when {
    r.failed.isEmpty() -> if (r.ok.size == 1) "1 temps saisi" else "${r.ok.size} temps saisis"
    r.ok.isEmpty() -> if (r.failed.size == 1) "Temps non saisi" else "${r.failed.size} temps non saisis"
    else -> "${r.ok.size} saisi${if (r.ok.size > 1) "s" else ""}, ${r.failed.size} en échec"
  }

  /** Corps : une ligne par échec, avec sa raison telle que le bridge la donne. */
  fun resultText(r: TimeResult): String {
    if (r.failed.isEmpty()) return "C'est enregistré dans le système de tickets."
    return r.failed.joinToString("\n") { f ->
      val t = ticketOf(f.id)
      (if (t != null) "#$t : " else "") + f.reason
    }
  }

  /** Texte du bouton « Copier » : le `copy_text` de chaque échec, précédé du ticket quand il y en a plusieurs. Null si rien à copier. */
  fun copyBlock(r: TimeResult): String? {
    val withText = r.failed.filter { it.copyText != null }
    if (withText.isEmpty()) return null
    if (withText.size == 1) return withText[0].copyText
    return withText.joinToString("\n") { f ->
      val t = ticketOf(f.id)
      (if (t != null) "#$t : " else "") + f.copyText
    }
  }
}
