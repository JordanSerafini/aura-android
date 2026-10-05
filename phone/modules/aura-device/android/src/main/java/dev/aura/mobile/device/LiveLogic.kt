package dev.aura.mobile.device

import org.json.JSONObject

/*
 * Live Update des taches longues d'Aura (LiveUpdate.kt) : regles pures, testees en JVM (LiveLogicTest).
 *
 * Deux sortes de tours : ceux partis de CE telephone (montre, Talk, assistant, partage, PWA de l'onglet Aura) sont
 * suivis des le premier mot, avec un bouton « Arreter » ; ceux partis d'ailleurs (autre client du bridge, autre onglet) sont
 * « etrangers » : le bridge diffuse leur etat a tous les clients (`state`, `delta`), et le telephone ne les montre
 * qu'une fois LONGS (la tache est longue : l'utilisateur est peut-etre parti du PC), sans bouton Arreter (ce n'est pas sa demande).
 */
object LiveLogic {
  /** Un tour etranger n'apparait qu'au bout de 90 s : une reponse courte du PC ne doit rien faire sur le telephone. */
  const val LONG_MS = 90_000L

  fun visible(foreign: Boolean, elapsedMs: Long): Boolean = !foreign || elapsedMs >= LONG_MS

  /** Delai avant que ce tour devienne visible (0 = deja visible). */
  fun dueIn(foreign: Boolean, startedMs: Long, nowMs: Long): Long = if (!foreign) 0L else (startedMs + LONG_MS - nowMs).coerceAtLeast(0L)

  fun title(foreign: Boolean, step: String): String = if (foreign) "Aura sur le PC · $step" else "Aura · $step"

  /** Le bouton « Arreter » n'existe que pour une demande partie d'ici. */
  fun canStop(foreign: Boolean, id: String): Boolean = !foreign && id.isNotEmpty()

  /** Texte de repli d'un tour etranger sans outil ni texte : depuis combien de temps. */
  fun waitingText(foreign: Boolean, elapsedMs: Long): String {
    if (!foreign) return "Réflexion en cours…"
    return "Tâche en cours depuis ${(elapsedMs / 60_000L).toInt().coerceAtLeast(1)} min"  // visible a partir de 90 s : au moins 1 min
  }

  /** Titre de la version publique (ecran verrouille) d'un tour etranger : ni etape, ni outil, ni texte. */
  const val PUBLIC_TITLE = "Aura travaille…"

  /**
   * Un tour parti du PC (texte streame, noms d'outils : fichiers, commandes) ne doit pas se lire sur l'ecran verrouille :
   * visibilite PRIVATE + version publique neutre. Les demandes parties d'ici gardent leur comportement.
   */
  fun lockScreenPrivate(foreign: Boolean): Boolean = foreign

  /** « Bash · date » -> « Bash » ; « transcription » -> « Transcription ». */
  fun toolName(label: String): String {
    val head = label.substringBefore(" · ").trim().ifEmpty { label }
    return head.replaceFirstChar { it.uppercase() }.take(24)
  }

  /** Puce de la barre d'etat : l'outil en cours (12 car.) ; jamais pour un tour etranger, la puce se voit verrouille. */
  fun chip(foreign: Boolean, tool: String?, toolActive: Boolean): String? =
    if (foreign || !toolActive || tool == null) null else toolName(tool).take(12)

  // ─── run_state (PROTOCOL.md §11.D) : la progression vient du bridge, pas d'une minuterie du telephone ───

  /** Ligne de progression diffusee par le bridge toutes les 5 s, des 30 s de tour : etape (outil, redaction, reflexion) et duree. */
  class RunState(val conv: String, val elapsedS: Long, val step: String)

  const val STEP_WRITING = "rédaction de la réponse"
  const val STEP_THINKING = "réflexion"
  /** Un tour de plus de 24 h n'existe pas : une duree plus grande est une valeur folle, pas une horloge a croire. */
  const val MAX_ELAPSED_S = 24 * 3600L

  /** `{conv, state: "running", elapsed_s, step}` -> RunState ; null si `conv` ou `elapsed_s` manquent ou sont fous. */
  fun parseRunState(msg: JSONObject): RunState? {
    val conv = if (msg.isNull("conv")) "" else (msg.opt("conv") as? String ?: "")
    if (conv.isEmpty() || conv.length > 64) return null
    val e = msg.opt("elapsed_s") as? Number ?: return null
    val d = e.toDouble()
    if (d.isNaN() || d < 0 || d > MAX_ELAPSED_S) return null
    val step = ((msg.opt("step") as? String) ?: "").replace(Regex("[\\u0000-\\u001f\\u007f-\\u009f]+"), " ").trim().take(120)
    return RunState(conv, d.toLong(), step)
  }

  enum class StepKind { TOOL, WRITING, THINKING }

  /** `step` = l'outil en cours (« Bash · rsync … »), sinon « rédaction de la réponse » ou « réflexion ». */
  fun stepKind(step: String): StepKind = when (step.trim().lowercase()) {
    STEP_WRITING -> StepKind.WRITING
    "", STEP_THINKING -> StepKind.THINKING
    else -> StepKind.TOOL
  }

  /**
   * Debut du tour d'apres la duree donnee par le bridge : le plus ANCIEN des deux. Un tour que le telephone n'a pas vu
   * commencer (reconnexion, app relancee) retrouve ainsi sa vraie duree ; un tour suivi depuis son premier mot ne rajeunit jamais.
   */
  fun startedFrom(currentMs: Long, nowMs: Long, elapsedS: Long): Long = minOf(currentMs, nowMs - elapsedS * 1000L)
}
