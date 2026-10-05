package dev.aura.mobile.device

import org.json.JSONObject

/*
 * Fin d'une tâche d'Aura (`task_done`, PROTOCOL.md §11.D). Logique pure, testée en JVM (FinLogicTest) ; TaskDone.kt en est
 * la colle (notification heads-up).
 *
 * Le bridge pousse déjà, quand personne ne regarde la conversation, une notification `aura-fin` (historique des notifs,
 * boutons Archiver / Moins de ça). Le heads-up natif ne doit pas la doubler : voir FinDedup.
 */
class TaskDoneEvent(val conv: String, val title: String, val ok: Boolean, val summary: String, val elapsedS: Int)

object FinLogic {
  const val TITLE_MAX = 80
  const val SUMMARY_MAX = 200
  /** Le heads-up natif attend un peu : le bridge diffuse `task_done` AVANT la notification `aura-fin` qui doit le remplacer. */
  const val GRACE_MS = 2_500L
  /** Une notification `aura-fin` de cette conversation, vue il y a moins de 60 s, vaut le heads-up natif. */
  const val DEDUP_WINDOW_MS = 60_000L

  private val CONV = Regex("^[A-Za-z0-9_-]{1,64}$")

  /** `{conv, title, ok, summary, elapsed_s}` -> événement nettoyé ; null si `conv` manque ou est mal formé. */
  fun parse(msg: JSONObject): TaskDoneEvent? {
    val conv = if (msg.isNull("conv")) "" else (msg.opt("conv") as? String ?: "")
    if (!CONV.matches(conv)) return null
    val title = CallCardLogic.clean(msg.opt("title") as? String, TITLE_MAX).ifEmpty { "Aura" }
    val summary = CallCardLogic.clean(msg.opt("summary") as? String, SUMMARY_MAX)
    val ok = msg.opt("ok") != false  // absent : réussi ; seul `false` est un échec
    val elapsed = msg.optInt("elapsed_s", 0).coerceAtLeast(0)
    return TaskDoneEvent(conv, title, ok, summary, elapsed)
  }

  /** « Aura a fini : <titre> » (le titre du chat). */
  fun title(e: TaskDoneEvent): String = if (e.ok) "Aura a fini : ${e.title}" else "Aura n'a pas pu finir : ${e.title}"

  /** « 2 min 05 » ou « 45 s ». */
  fun elapsedLabel(s: Int): String {
    if (s < 60) return "$s s"
    return "${s / 60} min ${"%02d".format(s % 60)}"
  }

  /** Corps : le résumé, sinon la durée. */
  fun body(e: TaskDoneEvent): String = e.summary.ifEmpty { "Terminé en ${elapsedLabel(e.elapsedS)}" }

  /**
   * Heads-up seulement si l'utilisateur n'a pas déjà la réponse sous les yeux : l'app au premier plan sur l'onglet Aura (la PWA
   * déclare elle-même la conversation vue au bridge, `viewing`) ou sur Talk.
   */
  fun shouldShow(appForeground: Boolean, screen: String): Boolean = !(appForeground && (screen == "Aura" || screen == "Talk"))
}

/**
 * Pas de doublon avec `aura-fin` : par conversation, la dernière notification `aura-fin` vue. Le heads-up natif est refusé si
 * une `aura-fin` de la même conversation est arrivée dans les 60 s qui précèdent le moment où l'on décide (le bridge envoie
 * `task_done` d'abord, puis `aura-fin` : le natif décide donc après un court délai, FinLogic.GRACE_MS).
 */
class FinDedup(private val windowMs: Long = FinLogic.DEDUP_WINDOW_MS) {
  private val seen = HashMap<String, Long>()

  @Synchronized fun onBridgeFin(conv: String, nowMs: Long) {
    seen[conv] = nowMs
    if (seen.size > 50) seen.entries.removeAll { nowMs - it.value > windowMs }
  }

  @Synchronized fun duplicate(conv: String, nowMs: Long): Boolean {
    val t = seen[conv] ?: return false
    return nowMs - t <= windowMs
  }
}
