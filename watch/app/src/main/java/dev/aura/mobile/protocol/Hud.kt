package dev.aura.mobile.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * HUD du PC sur la montre (0.3.0, 22/10, PROTOCOL.md §35) : resume publie par le telephone 1.9.0 dans `/aura/status`
 * (champ `hud`), affiche par la tuile et la complication « Aura HUD ». Logique pure, testee en JVM (HudWatchTest).
 */
@Serializable
data class HudBrief(
  val n: Int = 0,
  val graves: Int = 0,
  val todo: Int = 0,
  /** « En cours → 11:00 · X », « 14:00 · X », « Demain 08:30 · X » ; vide = aucun rendez-vous. */
  val rdv: String = "",
  @SerialName("rdv_ts") val rdvTs: Long = 0L,
  /** Reception du flux par le telephone (epoch s). */
  val at: Long = 0L,
)

object HudWatch {
  /** Au-dela, le HUD du telephone est vieux : le PC ne repond plus (le bridge renvoie le flux toutes les 10 min au moins). */
  const val STALE_S = 20 * 60L

  enum class Level { OK, ALERT, GRAVE, UNKNOWN }

  data class Model(val headline: String, val level: Level, val rdv: String, val todo: String, val shortText: String,
                   val title: String, val description: String)

  fun level(h: HudBrief?): Level = when {
    h == null -> Level.UNKNOWN
    h.graves > 0 -> Level.GRAVE
    h.n > 0 -> Level.ALERT
    else -> Level.OK
  }

  fun model(status: AuraStatus?, nowS: Long): Model {
    val h = status?.hud
    if (h == null) return Model("HUD indisponible", Level.UNKNOWN, "Ouvre Aura sur le téléphone", "", "–", "HUD",
      "HUD d'Aura indisponible")
    val old = h.at <= 0 || nowS - h.at > STALE_S
    val lv = level(h)
    val headline = when {
      old -> "PC injoignable"
      h.n == 0 -> "Rien à signaler"
      h.graves > 0 -> "${h.n} alerte${if (h.n > 1) "s" else ""} · ${h.graves} grave${if (h.graves > 1) "s" else ""}"
      else -> "${h.n} alerte${if (h.n > 1) "s" else ""}"
    }
    val todo = if (h.todo > 0) "${h.todo} à traiter" else "Rien en attente"
    val rdv = h.rdv.ifEmpty { "Aucun rendez-vous" }
    // complication courte : nombre d'alertes (« ⚠2 » si grave), titre = heure du prochain rendez-vous
    val short = when {
      old -> "?"
      h.graves > 0 -> "⚠${h.n}"
      else -> "${h.n}"
    }
    val hour = Regex("\\b\\d{2}:\\d{2}\\b").find(h.rdv)?.value ?: "HUD"
    return Model(headline, if (old) Level.UNKNOWN else lv, rdv, todo, short, hour, "$headline. $rdv. $todo")
  }
}
