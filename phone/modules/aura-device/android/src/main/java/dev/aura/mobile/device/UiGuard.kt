package dev.aura.mobile.device

import org.json.JSONArray
import org.json.JSONObject

/*
 * Garde-fous de `ui_act` (docs/PROTOCOL.md §9.2) : logique pure, sans Android, testee en JVM (UiGuardTest).
 *
 * Trois barrieres, dans cet ordre, TOUTES avant d'importuner l'utilisateur avec une demande de confirmation :
 *  1. liste NOIRE codee en dur (banques, paiement, mots de passe, authentificateurs, parametres systeme,
 *     Play Store, Aura elle-meme) : refus systematique, MEME si l'utilisateur a ajoute l'app a la liste blanche ;
 *  2. liste BLANCHE de paquets, dans les reglages de l'app (editable, WhatsApp seul par defaut) ;
 *  3. forme de la demande (op connu, parametres bornes).
 * La liste noire est dupliquee dans le bridge (desktop_bridge/actions.py) : un test garde les deux identiques.
 * Meilleur effort (un nom de paquet ne dit pas tout) : la vraie protection est la liste blanche, courte.
 */
object UiGuard {
  val OPS = listOf("tap", "type", "scroll", "back", "home", "wait_text")
  /** Ops qui peuvent valider / envoyer quelque chose : confirmation TOUJOURS, quoi qu'ait dit le bridge. */
  val OUTGOING_OPS = setOf("tap", "type")
  const val TEXT_MAX = 500
  const val LABEL_MAX = 120
  const val WAIT_MAX_S = 20
  const val WAIT_DEFAULT_S = 8
  const val WHITELIST_MAX = 50

  // Refus systematique. Trois niveaux : paquet exact, prefixe, mot-cle dans le nom. Chaque liste tient dans
  // un seul bloc (les tests la relisent depuis ce fichier pour la comparer a celle du bridge).
  val EXACT = setOf(
    "com.android.vending", "com.google.android.gms", "com.google.android.gsf", "com.sec.android.app.samsungapps",
    "com.android.settings", "com.android.systemui", "com.android.shell", "com.android.packageinstaller",
    "com.google.android.packageinstaller", "com.samsung.android.packageinstaller", "com.android.permissioncontroller",
    "com.google.android.permissioncontroller", "com.android.certinstaller", "com.android.providers.settings",
    "com.google.android.settings.intelligence", "com.samsung.android.lool",
    "com.samsung.android.spay", "com.samsung.android.spayfw", "com.samsung.android.samsungpass",
    "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user",
    "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy", "com.twofasapp",
    "com.beemdevelopment.aegis", "org.fedorahosted.freeotp", "io.ente.auth", "com.duosecurity.duomobile",
    "com.okta.android.auth", "com.yubico.yubioath",
    "com.x8bit.bitwarden", "com.lastpass.lpandroid", "com.agilebits.onepassword", "com.dashlane", "io.enpass.app",
    "me.proton.android.pass", "com.callpod.android_apps.keeper", "com.nordpass.android.app.password.manager",
    "keepass2android.keepass2android", "com.kunzisoft.keepass.free", "com.kunzisoft.keepass.pro",
    "dev.aura.mobile",
  )
  val PREFIXES = listOf(
    "com.android.settings.", "com.android.systemui.", "com.android.providers.", "com.google.android.gms.",
    "com.samsung.android.knox", "com.samsung.android.spay", "com.samsung.android.samsungpass",
    "com.bitwarden.", "com.lastpass.", "com.agilebits.", "com.onepassword.", "com.paypal.", "com.duosecurity.",
    "org.keepass",
  )
  val KEYWORDS = listOf(
    "bank", "banque", "banking", "bourso", "creditagricole", "creditmutuel", "cmcic", "bnpparibas",
    "societegenerale", "labanquepostale", "caissedepargne", "banquepopulaire", ".lcl.", "hellobank", "fortuneo",
    "revolut", "number26", "qonto", "lydia", "paylib", "monzo", "transferwise", "paypal", "wallet", "payment",
    "stripe", "twint", "payconiq", "alipay", "cashapp", "venmo", "coinbase", "binance", "metamask", "ledger",
    "authenticator", "authy", "totp", "freeotp", "password", "passwd", "keepass", "1password", "bitwarden",
    "lastpass", "dashlane",
  )

  /** Liste blanche tant que l'utilisateur n'y a pas touche : WhatsApp (l'envoi confirme existe deja), rien d'autre. */
  val DEFAULT_WHITELIST = listOf("com.whatsapp", "com.whatsapp.w4b")
  /** Noms courts acceptes dans `app` ; ils ne servent qu'a retrouver un paquet DE LA LISTE BLANCHE. */
  val ALIASES = mapOf(
    "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
    "telegram" to listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"),
    "signal" to listOf("org.thoughtcrime.securesms"),
    "messenger" to listOf("com.facebook.orca", "com.facebook.mlite"),
  )
  private val PKG = Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+")
  private val ALIAS = Regex("[a-z][a-z0-9_]{1,19}")

  fun blocked(pkg: String): Boolean {
    val p = pkg.trim().lowercase()
    return p in EXACT || PREFIXES.any { p.startsWith(it) } || KEYWORDS.any { p.contains(it) }
  }

  private fun blockedError() = ActionError("app_blocked", "App refusée d'office (banque, paiement, mots de passe, " +
    "authentificateur, paramètres système, Play Store) : jamais pilotable par Aura, même en liste blanche")

  fun needsConfirm(action: String, p: JSONObject): Boolean = action == "ui_act" && p.optString("op") in OUTGOING_OPS

  /** Liste blanche saisie / relue : minuscules, forme de paquet, JAMAIS un paquet de la liste noire, sans doublon. */
  fun normalize(list: Collection<String>): List<String> =
    list.map { it.trim().lowercase() }.filter { PKG.matches(it) && !blocked(it) }.distinct().take(WHITELIST_MAX)

  fun parseList(arr: JSONArray?): List<String> = normalize((0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it) })

  /** Demande validee : `pkgs` = paquets de la liste blanche que `app` peut designer (le premier au premier plan gagne). */
  class Plan(val pkgs: List<String>, val op: String, val text: String?, val desc: String?, val index: Int?,
             val direction: String, val waitS: Int, val replace: Boolean)

  /**
   * Valide une demande `ui_act` contre la liste blanche. ActionError : bad_params, app_blocked, app_not_allowed.
   * Ne regarde JAMAIS l'ecran : rien de ce qui s'y affiche n'entre ici.
   */
  fun plan(p: JSONObject, whitelist: List<String>): Plan {
    val app = p.str("app")?.trim()?.lowercase().orEmpty()
    val op = p.str("op")?.trim().orEmpty()
    if (op !in OPS) throw ActionError("bad_params", "op : ${OPS.joinToString(" | ")} attendu")
    // liste noire d'abord, sur ce que Claude a ecrit (paquet OU alias : « revolut » ne passe pas)
    if (blocked(app)) throw blockedError()
    val candidates: List<String> = when {
      PKG.matches(app) -> listOf(app)
      ALIAS.matches(app) -> ALIASES[app] ?: throw ActionError("bad_params",
        "app : « $app » inconnu, donne le nom de paquet (com.whatsapp) ou un alias (${ALIASES.keys.joinToString(", ")})")
      else -> throw ActionError("bad_params", "app : nom de paquet (com.whatsapp) ou alias (whatsapp) attendu")
    }
    if (candidates.any { blocked(it) }) throw blockedError()  // et sur ce que l'alias designe
    val allowed = candidates.filter { it in whitelist }
    if (allowed.isEmpty()) {
      throw ActionError("app_not_allowed", "« $app » n'est pas dans la liste blanche du téléphone " +
        "(Aura → Réglages → Contrôle d'apps) : à l'utilisateur de l'y ajouter")
    }
    fun text(key: String, limit: Int): String? {
      val v = p.str(key) ?: return null
      if (v.isBlank() || v.length > limit) throw ActionError("bad_params", "$key : texte de 1 à $limit caractères attendu")
      return v
    }
    var text: String? = null
    var desc: String? = null
    var index: Int? = null
    var direction = "down"
    var waitS = WAIT_DEFAULT_S
    var replace = false
    when (op) {
      "tap" -> {
        text = text("text", LABEL_MAX)
        desc = text("desc", LABEL_MAX)
        if (text == null && desc == null) throw ActionError("bad_params", "tap : text ou desc attendu")
        if (!p.isNull("index") && p.has("index")) {
          index = p.optInt("index", -1)
          if (index !in 0..20) throw ActionError("bad_params", "index : entier 0..20 attendu")
        }
      }
      "type" -> {
        text = text("text", TEXT_MAX) ?: throw ActionError("bad_params", "type : text attendu")
        replace = p.optBoolean("replace", false)
      }
      "scroll" -> {
        direction = p.str("direction") ?: "down"
        if (direction != "up" && direction != "down") throw ActionError("bad_params", "direction : up | down attendu")
      }
      "wait_text" -> {
        text = text("text", LABEL_MAX) ?: throw ActionError("bad_params", "wait_text : text attendu")
        if (!p.isNull("wait_s") && p.has("wait_s")) {
          waitS = p.optInt("wait_s", -1)
          if (waitS !in 1..WAIT_MAX_S) throw ActionError("bad_params", "wait_s : entier 1..$WAIT_MAX_S attendu")
        }
      }
    }
    return Plan(allowed, op, text, desc, index, direction, waitS, replace)
  }
}

/** Correspondance d'un libelle lu a l'ecran avec la demande : pure, testee en JVM. */
object UiMatch {
  fun norm(s: CharSequence?): String = s?.toString()?.trim()?.lowercase()?.replace(Regex("\\s+"), " ").orEmpty()

  /**
   * Indices des candidats retenus. Chaque candidat porte ses libelles (texte, description). D'abord l'egalite
   * exacte (casse et espaces ignores) ; a defaut seulement, « contient ». Jamais les deux melanges : « Envoyer »
   * ne doit pas viser aussi « Envoyer un fichier » quand l'exact existe.
   */
  fun pick(candidates: List<List<String>>, query: String): List<Int> {
    val q = norm(query)
    if (q.isEmpty()) return emptyList()
    val exact = candidates.indices.filter { i -> candidates[i].any { norm(it) == q } }
    if (exact.isNotEmpty()) return exact
    return candidates.indices.filter { i -> candidates[i].any { norm(it).contains(q) } }
  }
}
