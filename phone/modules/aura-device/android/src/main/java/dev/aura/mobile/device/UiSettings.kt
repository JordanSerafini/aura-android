package dev.aura.mobile.device

import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Liste blanche des apps pilotables par `ui_act` (PROTOCOL.md §9.2) : reglages de l'app, editable dans
 * Reglages → Contrôle d'apps. Absente des prefs = WhatsApp seul (UiGuard.DEFAULT_WHITELIST) ; une fois editee,
 * elle est gardee telle quelle, MEME VIDE (l'utilisateur qui coupe tout doit obtenir « rien », pas le defaut).
 * Les paquets de la liste noire ne sont jamais stockes ni retournes, meme si un fichier de prefs en contenait.
 */
object UiSettings {
  private const val PREF = "ui_whitelist"

  fun whitelist(): List<String> {
    val raw = Aura.prefs.getString(PREF, null) ?: return UiGuard.DEFAULT_WHITELIST
    return try { UiGuard.parseList(JSONArray(raw)) } catch (e: Exception) { UiGuard.DEFAULT_WHITELIST }
  }

  /** Enregistre la liste (normalisee : forme de paquet, sans doublon, sans paquet sensible) et rend le stocke. */
  fun setWhitelist(json: String): String {
    val list = try { UiGuard.parseList(JSONArray(json)) } catch (e: Exception) { UiGuard.DEFAULT_WHITELIST }
    val out = JSONArray(list).toString()
    Aura.prefs.edit().putString(PREF, out).apply()
    return out
  }

  /** Apps lancables installees, pour le selecteur des reglages : [{package, label, blocked}]. Triees par nom. */
  fun listApps(): String {
    val pm = Aura.app.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val seen = HashSet<String>()
    val out = pm.queryIntentActivities(main, PackageManager.MATCH_ALL).mapNotNull { ri ->
      val pkg = ri.activityInfo.packageName
      if (pkg == Aura.app.packageName || !seen.add(pkg)) null
      else JSONObject().put("package", pkg).put("label", ri.loadLabel(pm).toString()).put("blocked", UiGuard.blocked(pkg))
    }.sortedBy { it.getString("label").lowercase() }
    return JSONArray(out).toString()
  }
}
