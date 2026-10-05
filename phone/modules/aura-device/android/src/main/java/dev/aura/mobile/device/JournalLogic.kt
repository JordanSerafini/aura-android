package dev.aura.mobile.device

import org.json.JSONArray
import org.json.JSONObject

/*
 * Journal Aura unifie : evenements du telephone (phone_event), actions executees (bridge ou test local) et gestes
 * ui_act, dans UNE liste courte (200 entrees, la plus recente d'abord) qui survit au redemarrage. Logique pure,
 * testee en JVM (JournalLogicTest) ; Journal.kt en est la colle (SharedPreferences, evenement JS).
 *
 * Vie privee : le journal vit dans les prefs privees de l'app (exclues des sauvegardes : allowBackup=false), jamais
 * dans logcat. Il ne garde PAS le titre ni le texte des notifications remontees (contenu de tiers) : seulement l'app.
 * Il ne garde pas non plus ce que l'utilisateur ou Aura ECRIT : texte d'un SMS, d'un message, d'une reponse, d'une saisie
 * ui_act (redactedSummary : type de destinataire et longueur seulement). Un appel manque garde le nom ou le numero,
 * comme le journal d'appels du systeme. l'utilisateur peut tout effacer (« Effacer le journal »).
 *
 * Entree : {ts, type: "event"|"action", kind, summary, status, ...}. Les entrees d'action gardent les champs de
 * l'ancien journal (action, source, confirm, ok, error, ms) pour ne rien perdre de ce qu'il affichait.
 */
object JournalLogic {
  const val MAX = 200

  // statuts d'un evenement : envoye | queued (hors ligne, rejoue au prochain welcome) | offline (non envoye) |
  // filtered (anti-spam) | blocked (reglage, bridge ancien) | paused (Pause d'Aura)
  const val SENT = "sent"
  const val QUEUED = "queued"
  const val OFFLINE = "offline"
  const val FILTERED = "filtered"
  const val BLOCKED = "blocked"
  const val PAUSED = "paused"
  // statuts d'une action : ok | failed | refused | timeout, plus blocked et paused
  const val OK = "ok"
  const val FAILED = "failed"
  const val REFUSED = "refused"
  const val TIMEOUT = "timeout"

  private val BLOCKED_CODES = setOf("app_blocked", "app_not_allowed", "refused_field")

  fun actionStatus(ok: Boolean, error: String?): String = when {
    ok -> OK
    error == "paused" -> PAUSED
    error != null && error in BLOCKED_CODES -> BLOCKED
    error == "refused" -> REFUSED
    error == "timeout" -> TIMEOUT
    else -> FAILED
  }

  /** « Appel manque : Maman », « Notification : WhatsApp » (jamais le titre ni le texte). */
  fun eventSummary(kind: String, f: JSONObject): String = when (kind) {
    "missed_call" -> "Appel manqué : " + (f.str("name")?.takeIf { it.isNotBlank() } ?: f.str("number")?.takeIf { it.isNotBlank() } ?: "numéro masqué")
    "call_ringing" -> "Appel entrant"  // sans le numero : la fiche et son contenu ne sont ecrits nulle part
    "notification" -> "Notification : " + (f.str("app_name")?.takeIf { it.isNotBlank() } ?: f.str("app") ?: "app")
    "zone" -> (if (f.str("transition") == "exit") "Sortie de la zone " else "Entrée dans la zone ") + (f.str("zone") ?: "?")
    "battery_low" -> "Batterie à ${f.optInt("percent", -1).let { if (it < 0) "?" else it.toString() }} %"
    "charger" -> (if (f.optBoolean("connected")) "Chargeur branché" else "Chargeur débranché") +
      (if (f.has("percent")) " (${f.optInt("percent")} %)" else "")
    else -> kind
  }

  /** Actions dont le resume du bridge reprend un texte ecrit : jamais garde tel quel. */
  private val CONTENT_ACTIONS = setOf("sms_send", "message_send", "notif_reply", "email_compose", "clipboard_set")

  /** Cette action (et son geste `ui_act`) porte-t-elle un texte ecrit ? */
  fun carriesContent(action: String, op: String?): Boolean = action in CONTENT_ACTIONS || (action == "ui_act" && op == "type")

  /** « un numéro », « une adresse » ou « un contact » : le type du destinataire, jamais son nom ni son numero. */
  fun recipientKind(to: String?): String {
    val t = to?.trim().orEmpty()
    return when {
      t.contains('@') -> "une adresse"
      t.isNotEmpty() && t.all { it.isDigit() || it in "+ .-()" } -> "un numéro"
      else -> "un contact"
    }
  }

  private fun chars(n: Int?): String = if (n == null) "" else " ($n car.)"

  /**
   * Resume d'une action SANS le texte ecrit : « SMS à un contact (42 car.) ». Les autres actions gardent le resume du
   * bridge. `params` absent (test local) : le libelle seul.
   */
  fun redactedSummary(action: String, summary: String, params: JSONObject?, op: String? = null): String {
    val o = op ?: params?.str("op")
    if (!carriesContent(action, o)) return summary
    val to = params?.str("to")
    fun len(key: String) = params?.str(key)?.length
    return when (action) {
      "sms_send" -> "SMS à ${recipientKind(to)}${chars(len("text"))}"
      "message_send" -> "Message ${params?.str("app")?.take(30) ?: ""} à ${recipientKind(to)}${chars(len("text"))}".replace("  ", " ")
      "notif_reply" -> "Réponse à une notification${chars(len("text"))}"
      "email_compose" -> "E-mail à ${recipientKind(to)}${chars((len("body") ?: 0) + (len("subject") ?: 0))}"
      "clipboard_set" -> "Copie dans le presse-papiers${chars(len("text"))}"
      else -> "Saisie dans ${params?.str("app")?.take(40) ?: "une app"}${chars(len("text"))}"  // ui_act type
    }
  }

  /** Libelle d'une entree ANCIENNE (ecrite avant le masquage) dont le texte n'est plus connu. */
  private fun legacyLabel(action: String): String = when (action) {
    "sms_send" -> "SMS"
    "message_send" -> "Message"
    "notif_reply" -> "Réponse à une notification"
    "email_compose" -> "E-mail"
    "clipboard_set" -> "Copie dans le presse-papiers"
    else -> "Saisie dans une app"
  }

  /** Entree deja au journal avant le masquage : le texte est remplace par un libelle. true si elle a ete reecrite. */
  fun scrub(e: JSONObject): Boolean {
    if (e.optString("type") != "action" || e.optBoolean("masked")) return false
    val action = e.optString("action")
    if (!carriesContent(action, e.str("op"))) return false
    e.put("summary", "${legacyLabel(action)} (texte non conservé)").put("masked", true)
    return true
  }

  /** Un evenement vers le bridge : l'entree du journal. */
  fun eventEntry(kind: String, f: JSONObject, status: String, detail: String? = null): JSONObject =
    JSONObject().put("type", "event").put("kind", kind).put("summary", eventSummary(kind, f)).put("status", status)
      .put("source", "phone").apply { if (detail != null) put("detail", detail.take(120)) }

  /** Une action (bridge ou test local) : garde les champs de l'ancien journal. */
  fun actionEntry(action: String, summary: String, source: String, confirm: Boolean, ok: Boolean, error: String?, ms: Long,
                  app: String? = null, op: String? = null, params: JSONObject? = null): JSONObject {
    val content = carriesContent(action, op ?: params?.str("op"))
    return JSONObject().put("type", "action").put("kind", action).put("action", action)
      .put("summary", redactedSummary(action, summary, params, op).take(200))
      .put("status", actionStatus(ok, error)).put("source", source).put("confirm", confirm).put("ok", ok)
      .put("error", error ?: JSONObject.NULL).put("ms", ms)
      .apply {
        if (app != null) put("app", app.take(60))
        if (op != null) put("op", op)
        if (content) put("masked", true)
      }
  }

  /** Anciennes entrees (avant le 01/10 : {action, summary, source, confirm, ok, error, ms, ts}) : type et statut ajoutes. */
  fun migrate(old: JSONObject): JSONObject {
    if (old.has("type") && old.has("status")) return old
    return old.put("type", "action").put("kind", old.optString("action")).put("status", actionStatus(old.optBoolean("ok"), old.str("error")))
  }
}

/** Liste bornee, la plus recente d'abord. Pas de verrou ici : JournalLog (ou Journal.kt) synchronise. */
class JournalStore(private val max: Int = JournalLogic.MAX) {
  private val items = ArrayDeque<JSONObject>()

  val size: Int get() = items.size

  fun add(entry: JSONObject) {
    items.addFirst(entry)
    while (items.size > max) items.removeLast()
  }

  fun clear() = items.clear()

  fun toJson(): JSONArray = JSONArray(items.toList())

  /**
   * Relit un tableau stocke (le plus recent d'abord) ; illisible = vide. `fromLegacy` : ancien journal des actions.
   * Rend le nombre d'entrees dont le texte ecrit a ete retire (journal d'avant le masquage) : a re-ecrire.
   */
  fun load(raw: String?, fromLegacy: Boolean = false): Int {
    items.clear()
    val arr = try { JSONArray(raw ?: "[]") } catch (e: Exception) { return 0 }
    var scrubbed = 0
    for (i in 0 until minOf(arr.length(), max)) {
      val o = arr.optJSONObject(i) ?: continue
      val e = if (fromLegacy) JournalLogic.migrate(o) else o
      if (JournalLogic.scrub(e)) scrubbed++
      items.addLast(e)
    }
    return scrubbed
  }

  fun filtered(type: String?): List<JSONObject> = if (type == null) items.toList() else items.filter { it.optString("type") == type }
}

/**
 * Le journal et son ecriture, sous UN verrou : l'instantane est pris ET ecrit sans qu'un autre thread s'intercale
 * (avant, l'`apply()` partait hors verrou : deux ajouts concurrents pouvaient ecrire leurs instantanes a l'envers et
 * perdre l'entree la plus recente au redemarrage). `persist` recoit le JSON complet.
 */
class JournalLog(private val persist: (String) -> Unit, private val store: JournalStore = JournalStore()) {
  @Synchronized fun load(raw: String?, fromLegacy: Boolean = false) {
    if (store.load(raw, fromLegacy) > 0) persist(store.toJson().toString())  // texte ecrit retire d'un ancien journal : reecrit
  }

  @Synchronized fun add(entry: JSONObject) {
    store.add(entry)
    persist(store.toJson().toString())
  }

  @Synchronized fun clear() {
    store.clear()
    persist(store.toJson().toString())
  }

  @Synchronized fun json(): String = store.toJson().toString()

  @Synchronized fun size(): Int = store.size
}
