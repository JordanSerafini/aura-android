package dev.aura.mobile.device

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Limite d'usage et reprise automatique (`limit_state`, `limit_cleared`, `limit_cancel`, PROTOCOL.md §11.C). Logique pure,
 * testée en JVM (LimitLogicTest) ; LimitBanner.kt en est la colle (notification discrète + « Annuler la reprise »).
 */
class LimitEvent(
  val conv: String,
  /** session | monthly */
  val kind: String,
  /** epoch secondes, null si le texte n'avait pas d'heure */
  val resetsAt: Long?,
  /** null = aucune reprise automatique prévue (mensuelle, heure illisible, ou reprise déjà tentée) */
  val retryAt: Long?,
  val message: String,
  val retrying: Boolean,
)

object LimitLogic {
  const val MESSAGE_MAX = 300
  private val CONV = Regex("^[A-Za-z0-9_-]{1,64}$")
  /** L'heure de la limite est donnée « heure de Paris » par le bridge : on l'affiche dans ce fuseau, où que soit le téléphone. */
  val PARIS: ZoneId = ZoneId.of("Europe/Paris")
  private val HM = DateTimeFormatter.ofPattern("HH:mm")

  private fun epoch(o: JSONObject, key: String): Long? {
    if (o.isNull(key) || !o.has(key)) return null
    val v = o.opt(key)
    if (v !is Number) return null
    val d = v.toDouble()
    if (d.isNaN() || d <= 0) return null
    return (if (d > 1e11) d / 1000 else d).toLong()  // millisecondes tolérées, comme partout
  }

  fun parse(msg: JSONObject): LimitEvent? {
    val conv = if (msg.isNull("conv")) "" else (msg.opt("conv") as? String ?: "")
    if (!CONV.matches(conv)) return null
    val kind = if (msg.optString("kind") == "monthly") "monthly" else "session"
    return LimitEvent(conv, kind, epoch(msg, "resets_at"), epoch(msg, "retry_at"),
      CallCardLogic.clean(msg.opt("message") as? String, MESSAGE_MAX), msg.opt("retrying") == true)
  }

  fun hhmm(epochS: Long, zone: ZoneId = PARIS): String = HM.format(Instant.ofEpochSecond(epochS).atZone(zone))

  /** Une reprise est prévue et pas encore partie : le bouton « Annuler la reprise » a un sens. */
  fun canCancel(e: LimitEvent): Boolean = !e.retrying && e.retryAt != null

  /** Titre : « Limite atteinte — Aura reprend à 19:30 » ; sans reprise prévue, juste « Limite atteinte ». */
  fun title(e: LimitEvent, zone: ZoneId = PARIS): String = when {
    e.retrying -> "Aura reprend maintenant"
    e.retryAt != null -> "Limite atteinte — Aura reprend à ${hhmm(e.resetsAt ?: e.retryAt, zone)}"
    else -> "Limite atteinte"
  }

  /** Corps : la phrase du bridge (déjà en français), sinon une phrase de repli. */
  fun body(e: LimitEvent): String = when {
    e.retrying -> "La dernière demande est relancée."
    e.message.isNotEmpty() -> e.message
    e.retryAt != null -> "Aura relancera ta dernière demande."
    else -> "Aura ne relance pas toute seule."
  }
}
