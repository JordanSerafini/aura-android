package dev.aura.mobile.device

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Widget d'ecran d'accueil (17/10 soir) : textes du widget, logique pure testee sur la JVM. Les nombres sont ceux des
 * pastilles de la PWA (« À traiter » = groupe « Maintenant », Notifs = non lues), envoyes par l'app quand elle les voit :
 * le widget ne sait rien d'autre. Il dit donc QUAND il a su (« à 21:40 », « le 17/10 à 21:40 »), et « Ouvre Aura » s'il
 * n'a jamais rien recu : un 0 invente dirait « rien a faire » a tort.
 */
object WidgetLogic {
  data class Texts(val actions: String, val notifs: String, val at: String, val actionsA11y: String, val notifsA11y: String)

  private val HM = DateTimeFormatter.ofPattern("HH:mm")
  private val DM = DateTimeFormatter.ofPattern("dd/MM")

  fun count(n: Int): String = if (n > 9) "9+" else n.coerceAtLeast(0).toString()

  fun at(atMs: Long, nowMs: Long, zone: ZoneId): String {
    if (atMs <= 0L) return "Ouvre Aura pour actualiser"
    val a = Instant.ofEpochMilli(atMs).atZone(zone)
    val n = Instant.ofEpochMilli(nowMs).atZone(zone)
    return if (a.toLocalDate() == n.toLocalDate()) "à ${a.format(HM)}" else "le ${a.format(DM)} à ${a.format(HM)}"
  }

  fun texts(actions: Int, notifs: Int, atMs: Long, nowMs: Long, zone: ZoneId): Texts {
    val known = atMs > 0L
    val whenTxt = at(atMs, nowMs, zone)
    val a = if (known) count(actions) else "–"
    val nf = if (known) count(notifs) else "–"
    val aA11y = if (!known) "À traiter : inconnu, $whenTxt"
      else "À traiter : ${if (actions == 0) "rien maintenant" else "$actions maintenant"}, $whenTxt. Ouvrir"
    val nA11y = if (!known) "Notifications : inconnu" else "Notifications non lues : $notifs. Ouvrir"
    return Texts(a, nf, whenTxt, aA11y, nA11y)
  }
}
