package dev.aura.mobile.device

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Widget « Aura HUD » (22/10) : l'equivalent telephone du HUD plein ecran que le serveur affiche sur le poste.
 * Logique pure, testee sur la JVM : lecture du flux `hud_feed` (PROTOCOL.md §33), taille du
 * widget, lignes a afficher, fraicheur, double appui des actions fortes. Le rendu (RemoteViews) est dans AuraHudWidget.kt.
 */
object HudLogic {
  enum class Size { SMALL, MEDIUM, LARGE }

  /** Gravite du noyau : couleur du rond et libelle lu par TalkBack. */
  enum class Level { OK, ALERTE, GRAVE, INCONNU }

  enum class Tone { VIOLET, GREEN, AMBER, RED, MUTED, CYAN }

  data class Btn(val label: String, val action: String, val id: String, val tone: Tone, val strong: Boolean)

  /** Une ligne de la liste du grand format : en-tete de section (`section`) ou element (titre, detail, boutons, lien). */
  data class Row(
    val key: String,
    val title: String,
    val detail: String = "",
    val tone: Tone = Tone.VIOLET,
    val section: Boolean = false,
    val open: String = "aura://view/chat",
    val buttons: List<Btn> = emptyList(),
    val time: String = "",
  )

  data class Header(val core: String, val level: Level, val coreA11y: String, val next: String, val fresh: String,
                    val offline: Boolean, val weather: String, val mode: String = "", val status: String = "",
                    val health: Tone = Tone.MUTED)

  /** Actions fortes : un 2e appui dans CONFIRM_MS confirme (le 1er arme le bouton, libelle « CONFIRMER ? »). */
  val STRONG = setOf("heal", "ignore", "promesse_ignore", "task_run")
  const val CONFIRM_MS = 6_000L
  /** Au-dela, le widget dit « PC injoignable · il y a N min » (le bridge renvoie le flux toutes les 10 min au moins). */
  const val STALE_MS = 20 * 60_000L
  /** Repli par HTTP (onUpdate, toutes les 30 min) seulement si le flux a plus de 15 min. */
  const val REFETCH_MS = 15 * 60_000L
  /** Un bouton « … » (action en cours) redevient normal au-dela : processus tue pendant l'envoi = « … » eternel (1.8.0). */
  const val PENDING_TTL_MS = 45_000L

  private val HM = DateTimeFormatter.ofPattern("HH:mm")

  fun size(widthDp: Int, heightDp: Int): Size = when {
    widthDp >= 220 && heightDp >= 230 -> Size.LARGE
    widthDp >= 220 -> Size.MEDIUM
    else -> Size.SMALL
  }

  /**
   * Lignes « À traiter » du format moyen selon sa hauteur (dp) : en-tete ~100 dp mesure sur le lanceur Pixel (barre de sante, RDV, statut,
   * fraicheur), une ligne = 48 dp (cible tactile). 0 = en-tete seul. Avant la 1.9.0, toujours 3 lignes de 40 dp : a 200 dp
   * et police 130 %, les lignes se chevauchaient (capture avant_apercu_police130).
   */
  fun todoLines(heightDp: Int): Int = ((heightDp - 100) / 48).coerceIn(0, 3)

  /**
   * Gabarits responsifs (Android 12+, `RemoteViews(Map<SizeF, RemoteViews>)`) : le lanceur choisit le plus grand qui
   * tient dans la taille reelle du widget, sans dependre de OPTION_APPWIDGET_MIN_WIDTH / MAX_HEIGHT (portrait seulement).
   * (largeur dp, hauteur dp, format, lignes du moyen)
   */
  val RESPONSIVE: List<Triple<Pair<Float, Float>, Size, Int>> = listOf(
    Triple(100f to 90f, Size.SMALL, 0),
    Triple(200f to 90f, Size.MEDIUM, 0),
    Triple(200f to 150f, Size.MEDIUM, 1),
    Triple(200f to 196f, Size.MEDIUM, 2),
    Triple(200f to 244f, Size.MEDIUM, 3),
    Triple(200f to 300f, Size.LARGE, 0),
  )

  /** Le gabarit que prend un lanceur pour une taille donnee (le plus grand qui tient, sinon le plus petit). */
  fun pick(widthDp: Float, heightDp: Float): Pair<Size, Int> {
    val fit = RESPONSIVE.filter { it.first.first <= widthDp && it.first.second <= heightDp }
      .maxByOrNull { it.first.first * it.first.second } ?: RESPONSIVE.first()
    return fit.second to fit.third
  }

  fun level(feed: JSONObject?): Level {
    val n = feed?.optJSONObject("noyau") ?: return Level.INCONNU
    return when (n.optString("niveau")) {
      "grave" -> Level.GRAVE
      "alerte" -> Level.ALERTE
      "ok" -> Level.OK
      else -> Level.INCONNU
    }
  }

  fun ago(ms: Long): String {
    val min = ms / 60_000L
    return when {
      min < 1 -> "à l'instant"
      min < 60 -> "il y a $min min"
      min < 48 * 60 -> "il y a ${min / 60} h"
      else -> "il y a ${min / 1440} j"
    }
  }

  /** Heure de fraicheur : « à 08:41 » ; trop vieux : « PC injoignable · il y a 25 min » ; jamais recu : « Ouvre Aura ». */
  fun fresh(atMs: Long, nowMs: Long, zone: ZoneId): Pair<String, Boolean> {
    if (atMs <= 0L) return "Pas encore de données · touche ↻" to true
    val age = nowMs - atMs
    if (age > STALE_MS) return "PC injoignable · ${ago(age)}" to true
    return "à ${Instant.ofEpochMilli(atMs).atZone(zone).format(HM)}" to false
  }

  fun header(feed: JSONObject?, atMs: Long, nowMs: Long, zone: ZoneId): Header {
    val (f, off) = fresh(atMs, nowMs, zone)
    val lv = level(feed)
    val n = feed?.optJSONObject("noyau")
    val core = if (n == null) "–" else n.optInt("n").let { if (it > 99) "99+" else it.toString() }
    val a11y = when (lv) {
      Level.INCONNU -> "Alertes : inconnu"
      Level.OK -> "Aucune alerte"
      else -> "${n!!.optInt("n")} alerte(s), dont ${n.optInt("graves")} grave(s)"
    }
    val next = nextRdv(feed, nowMs, zone)?.let { rdvLabel(it, nowMs, zone) } ?: feed?.optJSONObject("rdv")?.let { r ->
      val jour = r.optString("jour").let { if (it == "Aujourd'hui") "" else "$it " }
      "$jour${r.optString("h")} · ${r.optString("t")}"
    } ?: "Aucun rendez-vous à venir"
    val m = feed?.optJSONObject("meteo")
    val weather = if (m == null || m.isNull("t")) "" else "${m.optInt("t")}° ${m.optString("txt")}".trim()
    val mode = feed?.optString("mode").orEmpty().let { if (it.isEmpty() || it == "null") "" else it.uppercase() }
    return Header(core, lv, a11y, next, f, off, weather, mode, status(feed, nowMs), health(feed, off))
  }

  private fun JSONArray?.objs(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

  private fun num(o: JSONObject?, k: String): String =
    if (o == null || o.isNull(k)) "–" else o.optDouble(k).let { if (it % 1.0 == 0.0) it.toLong().toString() else "%.1f".format(it).replace('.', ',') }

  /** Boutons d'un element « À traiter », dans l'ordre du HUD du PC. */
  fun todoButtons(t: JSONObject): List<Btn> {
    val id = t.optString("id")
    return when (t.optString("kind")) {
      "rappel" -> listOf(Btn("FAIT", "reminder_done", id, Tone.GREEN, false), Btn("+1H", "reminder_snooze", id, Tone.AMBER, false))
      "mail" -> listOf(Btn("TRAITÉ", "mail_seen", id, Tone.GREEN, false))
      "promesse" -> listOf(Btn("FAIT", "promesse_done", id, Tone.GREEN, false),
        Btn("ÉCARTER", "promesse_ignore", id, Tone.MUTED, true))
      else -> emptyList()
    }
  }

  fun todoRow(t: JSONObject, nowMs: Long, zone: ZoneId): Row {
    val kind = t.optString("kind")
    val (title, detail, tone) = when (kind) {
      "mail" -> Triple("✉ ${t.optString("t")}", "${t.optString("d")} · ${t.optDouble("age_h", 0.0).toLong()} h sans réponse", Tone.CYAN)
      "promesse" -> Triple("☎ Promesse · ${t.optString("t")}", t.optString("d"), Tone.AMBER)
      else -> {
        val q = t.optLong("quand") * 1000L
        val due = q in 1..nowMs
        val at = if (q > 0) Instant.ofEpochMilli(q).atZone(zone).format(HM) else ""
        Triple("⏰ ${t.optString("t")}", if (due) "échu · $at" else "à $at", if (due) Tone.RED else Tone.VIOLET)
      }
    }
    val open = if (kind == "rappel") "aura://view/rappels" else "aura://view/actions"
    return Row("todo:$kind:${t.optString("id")}", title, detail, tone, open = open, buttons = todoButtons(t))
  }

  /** Les 3 lignes « À traiter » du format moyen (les plus urgentes : le bridge les a deja triees). */
  fun topTodo(feed: JSONObject?, nowMs: Long, zone: ZoneId, n: Int = 3): List<Row> =
    feed?.optJSONArray("a_traiter").objs().take(n).map { todoRow(it, nowMs, zone) }

  /** Liste du grand format : ALERTES, PLANNING (aujourd'hui, demain), À TRAITER, SYSTÈME, SUPERVISEUR, EN COURS. */
  fun rows(feed: JSONObject?, nowMs: Long, zone: ZoneId): List<Row> {
    if (feed == null) return listOf(Row("vide", "Pas encore de données", "Touche ↻, ou ouvre Aura une fois", Tone.MUTED))
    val out = mutableListOf<Row>()
    val al = feed.optJSONArray("alertes").objs()
    val noyau = feed.optJSONObject("noyau")
    out += Row("s:alertes", "ALERTES · ${noyau?.optInt("n") ?: al.size}", section = true,
      tone = if (level(feed) == Level.GRAVE) Tone.RED else Tone.VIOLET)
    if (al.isEmpty()) out += Row("al:0", "Rien à signaler", tone = Tone.GREEN, open = "aura://hub")
    for (a in al) {
      val btns = mutableListOf<Btn>()
      if (a.optString("tache").isNotEmpty()) btns += Btn("RELANCER", "task_run", a.optString("tache"), Tone.GREEN, false)
      btns += Btn("RÉPARE", "heal", "", Tone.CYAN, true)
      btns += Btn("MASQUER 24H", "ignore", a.optString("id"), Tone.MUTED, true)
      out += Row("al:${a.optString("id")}", a.optString("nom").uppercase(), a.optString("detail"),
        if (a.optBoolean("grave")) Tone.RED else Tone.AMBER, open = "aura://hub", buttons = btns)
    }
    for (j in feed.optJSONArray("planning").objs()) {
      val evs = j.optJSONArray("evs").objs()
      out += Row("s:plan:${j.optString("d")}", "PLANNING · ${j.optString("j").uppercase()}", section = true)
      if (evs.isEmpty()) out += Row("plan:${j.optString("d")}:0", "Rien de prévu", tone = Tone.MUTED, open = "aura://view/rappels")
      for ((i, e) in evs.withIndex()) {
        val k = e.optString("k")
        val fin = e.optString("fin").let { if (it.isNotEmpty() && it != e.optString("h")) "–$it" else "" }
        val lieu = e.optString("lieu").let { if (it.isNotEmpty()) " · $it" else "" }
        val kind = when (k) { "rdv" -> "Rendez-vous"; "intervention" -> "Intervention"; "rappel" -> "Rappel"; else -> k }
        val btns = if (k == "rappel" && e.optString("id").isNotEmpty())
          listOf(Btn("FAIT", "reminder_done", e.optString("id"), Tone.GREEN, false),
            Btn("+1H", "reminder_snooze", e.optString("id"), Tone.AMBER, false)) else emptyList()
        out += Row("plan:${j.optString("d")}:$i", e.optString("t"), "$kind$lieu",
          if (e.optBoolean("passe")) Tone.MUTED else if (k == "rappel") Tone.VIOLET else Tone.CYAN,
          open = if (k == "rappel") "aura://view/rappels" else "aura://view/actions", buttons = btns,
          time = e.optString("h") + fin)
      }
    }
    val todo = feed.optJSONArray("a_traiter").objs()
    out += Row("s:todo", "À TRAITER · ${todo.size}", section = true)
    if (todo.isEmpty()) out += Row("todo:0", "Rien en attente", tone = Tone.GREEN, open = "aura://view/actions")
    for (t in todo) out += todoRow(t, nowMs, zone)
    feed.optJSONObject("systeme")?.let { s ->
      out += Row("s:sys", "SYSTÈME", section = true)
      val d = s.optJSONObject("disque")
      val ko = s.optJSONArray("ko")
      val koTxt = if (ko == null || ko.length() == 0) "services OK" else "KO : " + (0 until ko.length()).joinToString(", ") { ko.optString(it) }
      out += Row("sys", "RAM ${num(s, "ram")} % · charge ${num(s, "charge")} % · swap ${num(s, "swap")} %",
        (if (d != null) "Disque le plus plein : ${d.optString("nom")} ${d.optInt("pct")} % · " else "") + koTxt,
        if (ko != null && ko.length() > 0 || (d?.optInt("pct") ?: 0) >= 90) Tone.AMBER else Tone.GREEN, open = "aura://hub")
    }
    feed.optJSONObject("superviseur")?.let { s ->
      out += Row("s:sup", "SUPERVISEUR", section = true)
      val age = if (s.isNull("age_min")) "" else " · il y a ${s.optInt("age_min")} min"
      val busy = if (!s.isNull("en_cours_s")) "TOUR EN COURS · " else ""
      out += Row("sup", "$busy[${s.optString("marqueur")}]$age", s.optString("resume"), Tone.VIOLET, open = "aura://view/chat")
    }
    val missions = feed.optJSONArray("missions").objs()
    if (missions.isNotEmpty()) {
      out += Row("s:missions", "EN COURS · ${missions.size}", section = true)
      for ((i, m) in missions.withIndex()) {
        val since = m.optLong("since")
        val dur = if (since > 0) " · ${ago(nowMs - since * 1000L).removePrefix("il y a ")}" else ""
        out += Row("mission:$i", m.optString("title") + dur, if (m.optBoolean("waiting")) "en file" else m.optString("step"),
          Tone.CYAN, open = "aura://view/chat")
      }
    }
    feed.optString("note").takeIf { it.isNotEmpty() }?.let {
      out += Row("s:note", "BLOC-NOTES", section = true)
      out += Row("note", it, tone = Tone.MUTED, open = "aura://view/chat")
    }
    return out
  }

  // ─── Appuis ────────────────────────────────────────────────────────────────

  sealed class Tap {
    /** Envoyer l'action au bridge. */
    data class Run(val action: String, val id: String) : Tap()
    /** 1er appui d'une action forte : armer le bouton (libelle « CONFIRMER ? ») jusqu'a `until`. */
    data class Arm(val key: String, val until: Long) : Tap()
    data class Refuse(val why: String) : Tap()
  }

  fun armKey(action: String, id: String) = "$action|$id"

  /** Decide d'un appui : `armed` = cle armee et son echeance (null si rien). Seules les actions connues passent. */
  fun tap(action: String, id: String, armed: Pair<String, Long>?, nowMs: Long): Tap {
    if (action !in ACTIONS) return Tap.Refuse("action inconnue")
    if (action != "heal" && id.isEmpty()) return Tap.Refuse("élément sans identifiant")
    if (action !in STRONG) return Tap.Run(action, id)
    val key = armKey(action, id)
    return if (armed != null && armed.first == key && nowMs <= armed.second) Tap.Run(action, id) else Tap.Arm(key, nowMs + CONFIRM_MS)
  }

  /** Meme liste que celle du serveur (lui seul fait foi : il refuse le reste). */
  val ACTIONS = setOf("reminder_done", "reminder_snooze", "task_run", "heal", "ignore", "mail_seen", "promesse_done",
    "promesse_ignore")

  fun actionBody(action: String, id: String): JSONObject = JSONObject().put("action", action).put("id", id)

  /** Libelle d'un bouton : armé = « CONFIRMER ? », en cours d'envoi = « … ». */
  fun label(b: Btn, armed: Pair<String, Long>?, pending: String?, nowMs: Long): String = when {
    pending == armKey(b.action, b.id) -> "…"
    armed != null && armed.first == armKey(b.action, b.id) && nowMs <= armed.second -> "CONFIRMER ?"
    else -> b.label
  }

  /** TalkBack : « FAIT : Rappeler Dupont » plutot que « FAIT » seul (le libelle ne dit pas sur quoi il agit). */
  fun btnA11y(b: Btn, rowTitle: String, armed: Pair<String, Long>?, pending: String?, nowMs: Long): String {
    val what = rowTitle.trimStart('✉', '☎', '⏰', ' ').take(60)
    return when (label(b, armed, pending, nowMs)) {
      "…" -> "${b.label} en cours : $what"
      "CONFIRMER ?" -> "Toucher encore pour confirmer ${b.label} : $what"
      else -> "${b.label} : $what"
    }
  }

  /** URL HTTP du bridge tiree de l'URL WebSocket : wss://hote/ws -> https://hote ; ws://ip:port/ws -> http://ip:port. */
  fun httpBase(wsUrl: String): String {
    val u = wsUrl.trim().substringBefore('#').substringBefore('?').removeSuffix("/").removeSuffix("/ws")
    return when {
      u.startsWith("wss://") -> "https://" + u.removePrefix("wss://")
      u.startsWith("ws://") -> "http://" + u.removePrefix("ws://")
      else -> u
    }
  }

  // ─── En-tete enrichi et HUD hors de l'ecran d'accueil (1.9.0, 22/10) ───────────

  /** Un rendez-vous (agenda ou intervention, cle « intervention » du flux) date : le flux ne donne que « 09:00 » et le jour `d` du planning. */
  data class Rdv(val title: String, val startMs: Long, val endMs: Long, val lieu: String, val kind: String)

  private fun at(d: String, h: String, zone: ZoneId): Long? = try {
    LocalDate.parse(d).atTime(LocalTime.parse(h)).atZone(zone).toInstant().toEpochMilli()
  } catch (e: Exception) { null }

  /** Rendez-vous du planning (aujourd'hui, demain), dans l'ordre ; sans heure de fin = 1 h. */
  fun rdvs(feed: JSONObject?, zone: ZoneId): List<Rdv> {
    val out = mutableListOf<Rdv>()
    for (j in feed?.optJSONArray("planning").objs()) {
      val d = j.optString("d")
      for (e in j.optJSONArray("evs").objs()) {
        val k = e.optString("k")
        if (k != "rdv" && k != "intervention") continue
        val start = at(d, e.optString("h"), zone) ?: continue
        val end = e.optString("fin").takeIf { it.isNotEmpty() }?.let { at(d, it, zone) }?.takeIf { it > start } ?: (start + 3_600_000L)
        out += Rdv(e.optString("t"), start, end, e.optString("lieu"), k)
      }
    }
    return out.sortedBy { it.startMs }
  }

  /** Le rendez-vous en cours, sinon le prochain ; un rendez-vous fini disparait (avant : « 09:00 · X » affiche a 10:45). */
  fun nextRdv(feed: JSONObject?, nowMs: Long, zone: ZoneId): Rdv? = rdvs(feed, zone).firstOrNull { it.endMs > nowMs }

  /** « En cours → 11:00 · X », « 14:00 · X », « Demain 08:30 · X », « ven. 09:00 · X ». */
  fun rdvLabel(r: Rdv, nowMs: Long, zone: ZoneId): String {
    val s = Instant.ofEpochMilli(r.startMs).atZone(zone)
    val lieu = if (r.lieu.isNotEmpty()) " · ${r.lieu}" else ""
    if (r.startMs <= nowMs) return "En cours → ${Instant.ofEpochMilli(r.endMs).atZone(zone).format(HM)} · ${r.title}$lieu"
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val jour = when (s.toLocalDate()) {
      today -> ""
      today.plusDays(1) -> "Demain "
      else -> s.format(DateTimeFormatter.ofPattern("EEE ", java.util.Locale.FRENCH))
    }
    return "$jour${s.format(HM)} · ${r.title}$lieu"
  }

  /** Ligne « Aura travaille » : missions detachees en cours, sinon tour du superviseur en cours ; vide sinon. */
  fun status(feed: JSONObject?, nowMs: Long): String {
    val ms = feed?.optJSONArray("missions").objs().filter { !it.optBoolean("waiting") }
    if (ms.isNotEmpty()) {
      val m = ms.first()
      val since = m.optLong("since").let { if (it > 0) " · " + ago(nowMs - it * 1000L).removePrefix("il y a ") else "" }
      val more = if (ms.size > 1) " (+${ms.size - 1})" else ""
      return "⚙ Aura travaille : ${m.optString("title")}$since$more"
    }
    val sup = feed?.optJSONObject("superviseur")
    if (sup != null && !sup.isNull("en_cours_s")) return "⚙ Superviseur : tour en cours"
    return ""
  }

  /** Barre de sante (haut du widget) : le pire de la gravite des alertes et de l'etat du PC ; muette hors ligne. */
  fun health(feed: JSONObject?, offline: Boolean): Tone {
    if (feed == null || offline) return Tone.MUTED
    val s = feed.optJSONObject("systeme")
    val ko = (s?.optJSONArray("ko")?.length() ?: 0) > 0
    val disk = s?.optJSONObject("disque")?.optInt("pct") ?: 0
    return when {
      level(feed) == Level.GRAVE || ko -> Tone.RED
      level(feed) == Level.ALERTE || disk >= 90 -> Tone.AMBER
      else -> Tone.GREEN
    }
  }

  /** Resume du HUD pour la notification persistante, la tuile et la montre. */
  data class Brief(val title: String, val text: String, val lines: List<String>, val level: Level, val alerts: Int,
                   val todo: Int, val rdv: String, val offline: Boolean)

  fun brief(feed: JSONObject?, atMs: Long, nowMs: Long, zone: ZoneId): Brief {
    val (fresh, off) = fresh(atMs, nowMs, zone)
    if (feed == null) return Brief("Aura", "HUD : pas encore de données", emptyList(), Level.INCONNU, 0, 0, "", true)
    val n = feed.optJSONObject("noyau")
    val nAl = n?.optInt("n") ?: 0
    val graves = n?.optInt("graves") ?: 0
    val todo = feed.optJSONArray("a_traiter")?.length() ?: 0
    val rdv = nextRdv(feed, nowMs, zone)?.let { rdvLabel(it, nowMs, zone) } ?: ""
    val title = when {
      nAl == 0 -> "Rien à signaler"
      graves > 0 -> "$nAl alerte${if (nAl > 1) "s" else ""} · $graves grave${if (graves > 1) "s" else ""}"
      else -> "$nAl alerte${if (nAl > 1) "s" else ""}"
    }
    val parts = mutableListOf<String>()
    if (rdv.isNotEmpty()) parts += rdv
    if (todo > 0) parts += "$todo à traiter"
    if (off) parts += fresh
    val lines = mutableListOf<String>()
    for (a in feed.optJSONArray("alertes").objs().sortedByDescending { it.optBoolean("grave") }.take(2)) {
      lines += (if (a.optBoolean("grave")) "⚠ " else "• ") + a.optString("nom").uppercase() + a.optString("detail").let { if (it.isEmpty()) "" else " · $it" }
    }
    if (rdv.isNotEmpty()) lines += "📅 $rdv"
    for (t in topTodo(feed, nowMs, zone, 3)) lines += "${t.title} · ${t.detail}"
    status(feed, nowMs).takeIf { it.isNotEmpty() }?.let { lines += it }
    if (off) lines += fresh
    return Brief(title, parts.joinToString(" · ").ifEmpty { "Planning libre, rien en attente" }, lines.take(7), level(feed),
      nAl, todo, rdv, off)
  }

  /** Sous-titre de la tuile Reglages rapides (court : la tuile tronque vers 20 caracteres). */
  fun tileSubtitle(b: Brief): String = when {
    b.level == Level.INCONNU -> "Pas de données"
    b.offline -> "PC injoignable"
    // court : la tuile de One UI et d'Android tronque vers 12-14 caracteres (« alertes · 8 à » vu sur l'emulateur)
    b.alerts > 0 -> "${b.alerts} alerte${if (b.alerts > 1) "s" else ""}"
    b.todo > 0 -> "${b.todo} à traiter"
    else -> "Tout va bien"
  }

  /** Nuit calme d'Aura (23 h-7 h) : aucune notification nouvelle ni promue pour le HUD. */
  fun nightQuiet(hour: Int): Boolean = hour >= 23 || hour < 7

  /** Fenetre du Live Update d'un rendez-vous : de 30 min avant le debut au debut (+ 5 min pour arriver). */
  const val RDV_LEAD_MS = 30 * 60_000L
  const val RDV_GRACE_MS = 5 * 60_000L

  /** Ce que la Live Update du HUD montre (une chose a la fois, la plus urgente) ; `progress` 0..100, -1 = indetermine. */
  data class Live(val kind: String, val key: String, val title: String, val text: String, val chip: String,
                  val countdownTo: Long, val progress: Int, val open: String)

  data class LiveOpts(val grave: Boolean = true, val rdv: Boolean = true, val missions: Boolean = false)

  /**
   * Choix de la Live Update : alerte GRAVE (flux frais seulement, pas une alerte vue il y a une heure) > rendez-vous dans
   * moins de 30 min > mission detachee en cours (option, coupee par defaut : il y en a souvent). `dismissed` = cles
   * ecartees par « Vu » (une nouvelle alerte grave a une autre cle et revient). Rien la nuit (23 h-7 h).
   */
  fun live(feed: JSONObject?, atMs: Long, nowMs: Long, zone: ZoneId, opts: LiveOpts, dismissed: Set<String>): Live? {
    if (feed == null) return null
    if (nightQuiet(Instant.ofEpochMilli(nowMs).atZone(zone).hour)) return null
    val fresh = atMs > 0 && nowMs - atMs <= STALE_MS
    if (opts.grave && fresh) {
      val g = feed.optJSONArray("alertes").objs().filter { it.optBoolean("grave") }
      if (g.isNotEmpty()) {
        val key = "grave:" + g.map { it.optString("id") }.sorted().joinToString(",")
        if (key !in dismissed) {
          val a = g.first()
          val more = if (g.size > 1) " (+${g.size - 1})" else ""
          return Live("grave", key, "⚠ ${a.optString("nom").uppercase()}$more", a.optString("detail").ifEmpty { "Alerte grave sur le PC" },
            "⚠ ${g.size}", 0L, -1, "aura://hub")
        }
      }
    }
    if (opts.rdv) {
      val r = rdvs(feed, zone).firstOrNull { nowMs >= it.startMs - RDV_LEAD_MS && nowMs <= it.startMs + RDV_GRACE_MS }
      if (r != null) {
        val key = "rdv:${r.startMs}:${r.title.hashCode()}"
        if (key !in dismissed) {
          val h = Instant.ofEpochMilli(r.startMs).atZone(zone).format(HM)
          val pct = (((nowMs - (r.startMs - RDV_LEAD_MS)).toDouble() / RDV_LEAD_MS) * 100).toInt().coerceIn(0, 100)
          val lieu = if (r.lieu.isNotEmpty()) " · ${r.lieu}" else ""
          val txt = if (nowMs >= r.startMs) "Commencé à $h$lieu" else "À $h$lieu"
          return Live("rdv", key, r.title, txt, h, if (nowMs < r.startMs) r.startMs else 0L, pct, "aura://view/actions")
        }
      }
    }
    if (opts.missions && fresh) {
      val m = feed.optJSONArray("missions").objs().firstOrNull { !it.optBoolean("waiting") }
      if (m != null) {
        val key = "mission:${m.optString("title")}:${m.optLong("since")}"
        if (key !in dismissed) {
          return Live("mission", key, "Aura travaille : ${m.optString("title")}", m.optString("step").ifEmpty { "en cours" },
            "Aura", 0L, -1, "aura://view/chat")
        }
      }
    }
    return null
  }

  /** Resume compact pour la montre (DataItem `/aura/status`, champ `hud`, PROTOCOL.md §35). Null sans flux. */
  fun watchBrief(feed: JSONObject?, atMs: Long, nowMs: Long, zone: ZoneId): JSONObject? {
    if (feed == null || atMs <= 0) return null
    val n = feed.optJSONObject("noyau")
    val r = nextRdv(feed, nowMs, zone)
    return JSONObject()
      .put("n", n?.optInt("n") ?: 0)
      .put("graves", n?.optInt("graves") ?: 0)
      .put("todo", feed.optJSONArray("a_traiter")?.length() ?: 0)
      .put("rdv", r?.let { rdvLabel(it, nowMs, zone).take(60) } ?: "")
      .put("rdv_ts", (r?.startMs ?: 0L) / 1000)
      .put("at", atMs / 1000)
  }
}
