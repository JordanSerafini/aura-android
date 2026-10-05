package dev.aura.mobile.device

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Service d'accessibilite OPTIONNEL (desactive par defaut, l'utilisateur l'active dans Parametres →
 * Accessibilite → Aura). Trois usages :
 *  - `screen_read` : texte visible de la fenetre active (PROTOCOL.md §7.4, balise non fiable par le bridge) ;
 *  - `message_send` WhatsApp sans notification : apres ouverture de la conversation pre-remplie, appui
 *    sur « Envoyer » a la place de l'utilisateur, UNIQUEMENT si la demande a ete confirmee par lui ;
 *  - `ui_act` (§9.2) : toucher, saisir, defiler, retour, accueil, attendre un texte, DANS LES APPS DE LA LISTE
 *    BLANCHE de l'utilisateur (reglages de l'app ; WhatsApp seul par defaut). Jamais dans une app sensible (banque,
 *    paiement, mots de passe, authentificateur, parametres systeme, Play Store : UiGuard.blocked, meme si elle
 *    figure dans la liste blanche). Toucher et saisir exigent sa confirmation (montre / telephone).
 * Ce que le service FAIT : agir dans ces apps-la, a la demande. Ce qu'il ne fait pas : ecouter les evenements
 * de l'ecran en continu (aucun suivi de ce que l'utilisateur fait ; onAccessibilityEvent ne fait rien), agir hors
 * liste blanche, ou executer un texte lu a l'ecran comme une instruction (le texte lu n'est que de la donnee).
 * Jusqu'au 28/09 ce commentaire disait « n'agit sur aucune autre appli » : faux pour ui_act, corrige ici.
 */
class AuraAccessibilityService : AccessibilityService() {
  override fun onServiceConnected() {
    Aura.init(this)
    instance = this
    Aura.bridge.capsChanged()
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
  override fun onInterrupt() = Unit

  override fun onDestroy() {
    instance = null
    if (Aura.serviceRunning) Aura.bridge.capsChanged()
    super.onDestroy()
  }

  companion object {
    @Volatile var instance: AuraAccessibilityService? = null
    private const val TEXT_MAX = 8000
    private val SEND_IDS = listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")
    private val SEND_LABELS = setOf("envoyer", "send")

    /** Active dans les reglages (meme si le systeme ne l'a pas encore lie). */
    fun enabled(): Boolean {
      val flat = ComponentName(Aura.app, AuraAccessibilityService::class.java).flattenToString()
      val list = Settings.Secure.getString(Aura.app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
      return list.split(':').any { it.equals(flat, ignoreCase = true) }
    }

    /** Nom lisible d'un paquet (journal, app_name) ; le paquet lui-meme si Android ne le connait pas. */
    fun appLabel(pkg: String): String = try {
      val pm = Aura.app.packageManager
      pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun svc(): AuraAccessibilityService = instance
      ?: throw ActionError("permission:accessibility", "Service d'accessibilité Aura désactivé (Paramètres → Accessibilité → Aura)")

    /** {app, app_name, text} de la fenetre active. */
    fun readScreen(): org.json.JSONObject {
      Pause.check("screen_read")  // Pause d'Aura : meme si l'appelant a oublie de la verifier
      val root = svc().rootInActiveWindow ?: throw ActionError("unavailable", "Aucune fenêtre lisible (écran éteint ou verrouillé ?)")
      val pkg = root.packageName?.toString() ?: ""
      Journal.noteGesture(appLabel(pkg), "lecture")  // journal : quelle app a ete lue (le garde-fou, lui, ne change pas)
      val sb = StringBuilder()
      fun walk(n: AccessibilityNodeInfo?, depth: Int) {
        if (n == null || sb.length >= TEXT_MAX || depth > 60) return
        val t = n.text?.toString()?.trim().orEmpty()
        val d = n.contentDescription?.toString()?.trim().orEmpty()
        val line = if (t.isNotEmpty()) t else if (n.childCount == 0) d else ""
        if (line.isNotEmpty() && n.isVisibleToUser) sb.append(line).append('\n')
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
      }
      walk(root, 0)
      val label = appLabel(pkg)
      return org.json.JSONObject().put("app", pkg).put("app_name", label).put("text", sb.toString().take(TEXT_MAX).trim())
    }

    /**
     * WhatsApp ouvert sur la conversation pre-remplie : attend le bouton Envoyer (8 s max) et appuie.
     * true si l'appui a eu lieu et que le champ de saisie s'est vide.
     */
    fun clickWhatsAppSend(timeoutMs: Long = 8000): Boolean {
      if (Pause.blocksApps()) return false  // Pause d'Aura : l'utilisateur appuie lui-meme sur Envoyer
      val s = instance ?: return false
      val deadline = System.currentTimeMillis() + timeoutMs
      while (System.currentTimeMillis() < deadline) {
        val root = s.rootInActiveWindow
        if (root != null && root.packageName?.toString()?.startsWith("com.whatsapp") == true) {
          val btn = SEND_IDS.asSequence().flatMap { root.findAccessibilityNodeInfosByViewId(it).asSequence() }.firstOrNull()
            ?: findByLabel(root)
          if (btn != null) {
            val target = generateSequence(btn) { it.parent }.firstOrNull { it.isClickable }
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
              Thread.sleep(700)
              val entry = s.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")?.firstOrNull()
              return entry == null || entry.text.isNullOrEmpty()
            }
          }
        }
        Thread.sleep(300)
      }
      return false
    }

    // ─── ui_act (PROTOCOL.md §9.2) ──────────────────────────────────────────

    private const val UI_NODE_BUDGET = 4000  // un arbre d'accessibilite geant ne doit pas figer le fil d'execution

    private fun labelOf(n: AccessibilityNodeInfo): String =
      n.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: n.contentDescription?.toString()?.trim().orEmpty()

    /** Parcours en profondeur (ordre de lecture) des noeuds VISIBLES ; `visit` rend false pour s'arreter. */
    private fun walkVisible(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Boolean) {
      var budget = UI_NODE_BUDGET
      fun go(n: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (n == null || depth > 60 || budget-- <= 0) return true
        if (n.isVisibleToUser && !visit(n)) return false
        for (i in 0 until n.childCount) if (!go(n.getChild(i), depth + 1)) return false
        return true
      }
      go(root, 0)
    }

    /**
     * Execute une demande `ui_act` DEJA validee contre la liste blanche (UiGuard.plan). Le paquet au premier
     * plan doit etre l'un des paquets autorises, sinon `wrong_app` : on n'agit jamais dans une autre app,
     * ni dans un paquet de la liste noire. Rien de ce qui est lu ici n'est interprete : ce sont des libelles a
     * comparer a la demande, renvoyes tels quels (le client les balise non fiables).
     */
    fun uiAct(plan: UiGuard.Plan): JSONObject {
      Pause.check("ui_act")  // Pause d'Aura : meme si l'appelant a oublie de la verifier
      val s = svc()
      val res = JSONObject().put("op", plan.op)
      if (plan.op == "home") {
        // journal : l'app qu'on quitte (home n'exige pas qu'une app precise soit au premier plan)
        s.rootInActiveWindow?.packageName?.toString()?.let { Journal.noteGesture(appLabel(it), "home") }
        if (!s.performGlobalAction(GLOBAL_ACTION_HOME)) throw ActionError("failed", "Android a refusé le retour à l'accueil")
        return res.put("done", true)
      }

      fun front(): Pair<AccessibilityNodeInfo, String> {
        val root = s.rootInActiveWindow ?: throw ActionError("unavailable", "Aucune fenêtre lisible (écran éteint ou verrouillé ?)")
        val pkg = root.packageName?.toString().orEmpty()
        if (UiGuard.blocked(pkg)) throw ActionError("app_blocked", "L'app au premier plan est refusée d'office : Aura n'y touche pas")
        if (pkg !in plan.pkgs) {
          throw ActionError("wrong_app", "L'app visée n'est pas au premier plan : ouvre ${plan.pkgs.first()} (open_app) puis réessaie",
            JSONObject().put("foreground", pkg))
        }
        Journal.noteGesture(appLabel(pkg), plan.op)  // journal : app ciblee et geste (scroll / back / home compris)
        return root to pkg
      }

      when (plan.op) {
        "tap" -> {
          val (root, _) = front()
          val query = plan.text ?: plan.desc.orEmpty()
          val nodes = mutableListOf<AccessibilityNodeInfo>()
          val labels = mutableListOf<List<String>>()
          walkVisible(root) { n ->
            if (!n.isPassword) {
              val t = n.text?.toString().orEmpty()
              val d = n.contentDescription?.toString().orEmpty()
              val l = if (plan.text != null) listOf(t, d) else listOf(d)  // desc : description seule
              if (l.any { it.isNotBlank() }) { nodes += n; labels += l }
            }
            true
          }
          val hits = UiMatch.pick(labels, query)
          if (hits.isEmpty()) throw ActionError("not_found", "Aucun élément visible « ${query.take(60)} » dans l'app")
          val idx = plan.index ?: if (hits.size == 1) 0 else throw ActionError("ambiguous",
            "${hits.size} éléments correspondent : précise index (0 à ${hits.size - 1}), voir result.candidates",
            JSONObject().put("candidates", JSONArray(hits.take(6).map { labelOf(nodes[it]).take(60) })))
          if (idx !in hits.indices) throw ActionError("bad_params", "index hors limites : ${hits.size} éléments")
          val target = nodes[hits[idx]]
          val clickable = generateSequence(target) { it.parent }.take(7).firstOrNull { it.isClickable && it.isEnabled }
            ?: throw ActionError("not_clickable", "Cet élément n'est pas cliquable")
          if (!clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw ActionError("failed", "Android a refusé l'appui")
          res.put("label", labelOf(target).take(80)).put("matched", hits.size)
        }
        "type" -> {
          val (root, pkg) = front()
          val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: throw ActionError("no_focus", "Aucun champ de saisie actif : touche d'abord le champ (op=tap)")
          if (f.packageName?.toString() != pkg) throw ActionError("wrong_app", "Le champ actif n'est pas dans l'app visée")
          if (f.isPassword) throw ActionError("refused_field", "Champ de mot de passe : jamais rempli par Aura")
          if (!f.isEditable) throw ActionError("no_focus", "Le champ actif n'est pas modifiable")
          val hint = Build.VERSION.SDK_INT >= 26 && f.isShowingHintText
          val cur = if (plan.replace || hint) "" else f.text?.toString().orEmpty()
          val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur + plan.text.orEmpty())
          }
          if (!f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw ActionError("failed", "Android a refusé la saisie")
          res.put("typed", plan.text.orEmpty().length).put("appended", cur.isNotEmpty())
        }
        "scroll" -> {
          val (root, _) = front()
          var scroller: AccessibilityNodeInfo? = null
          walkVisible(root) { n -> if (n.isScrollable) { scroller = n; false } else true }
          val target = scroller ?: throw ActionError("not_found", "Rien à faire défiler ici")
          val action = if (plan.direction == "down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
          res.put("scrolled", target.performAction(action))  // false : deja au bout
        }
        "back" -> {
          front()
          if (!s.performGlobalAction(GLOBAL_ACTION_BACK)) throw ActionError("failed", "Android a refusé le retour arrière")
          res.put("done", true)
        }
        "wait_text" -> {
          val query = plan.text.orEmpty()
          val deadline = System.currentTimeMillis() + plan.waitS * 1000L
          var seenApp = false
          while (true) {
            val root = s.rootInActiveWindow
            val pkg = root?.packageName?.toString().orEmpty()
            if (root != null && pkg in plan.pkgs && !UiGuard.blocked(pkg)) {
              seenApp = true
              var found = false
              // on ne lit QUE l'app visee : dans une autre, on attend sans regarder
              walkVisible(root) { n ->
                if (UiMatch.pick(listOf(listOf(n.text?.toString().orEmpty(), n.contentDescription?.toString().orEmpty())), query).isNotEmpty() && !n.isPassword) {
                  found = true; false
                } else true
              }
              if (found) return res.put("found", true)
            }
            if (System.currentTimeMillis() >= deadline) break
            Thread.sleep(300)
          }
          if (!seenApp) throw ActionError("wrong_app", "L'app visée n'est pas restée au premier plan")
          throw ActionError("not_found", "Texte « ${query.take(60)} » pas apparu en ${plan.waitS} s")
        }
      }
      return res
    }

    private fun findByLabel(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
      fun walk(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 40) return null
        val d = n.contentDescription?.toString()?.trim()?.lowercase()
        if (d != null && d in SEND_LABELS) return n
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)?.let { return it }
        return null
      }
      return walk(root, 0)
    }
  }
}
