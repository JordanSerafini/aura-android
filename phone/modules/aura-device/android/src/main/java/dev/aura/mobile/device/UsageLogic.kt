package dev.aura.mobile.device

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/*
 * Compteurs d'usage (`usage_report`, PROTOCOL.md §11.18) : des NOMBRES, jamais un contenu. Logique pure, testée en JVM
 * (UsageLogicTest) ; Usage.kt en est la colle (prefs, envoi).
 *
 * Sémantique du bridge : INCRÉMENTS depuis le dernier envoi (il additionne). Les compteurs sont donc rangés par jour
 * (`jour -> nom -> n`) et vidés à l'envoi ; un jour qui n'a pas fini reçoit le reste de ses incréments à l'envoi suivant,
 * sous le même `day`. Un rapport invalide est refusé EN ENTIER par le bridge : les noms et les valeurs sont donc bornés ici.
 *
 * Cadence (1.4.0) : au plus un envoi par TRANCHE DE 6 H (0-6, 6-12, 12-18, 18-24), au lieu d'un par jour. Mesure du 09/10 :
 * la veille passe toutes les 15 min, donc l'unique envoi du jour partait vers 00:15 avec la journée d'AVANT ; la section du
 * jour de `usage_counters.json` restait vide jusqu'au lendemain (« plus aucun ecran.* ni evt.* le 09/10 »), et une
 * réinstallation (08/10 16:49) effaçait tout ce qui attendait depuis minuit. Quatre envois par jour au plus, sur des passages
 * qui existent déjà (welcome, veille, arrière-plan) : aucun réveil de plus.
 */
typealias UsageBuckets = LinkedHashMap<String, LinkedHashMap<String, Int>>

object UsageLogic {
  /** Nom d'un compteur côté bridge : `^[a-z0-9_.]{1,40}$`. */
  private val NAME = Regex("^[a-z0-9_.]{1,40}$")
  private val DEVICE_BAD = Regex("[^A-Za-z0-9_.-]")
  const val MAX_PER_REPORT = 60
  /** usage_report par passage (le bridge en accepte 6 par minute et par socket). */
  const val MAX_REPORTS_PER_PASS = 5
  const val MAX_VALUE = 100_000
  /** Noms distincts gardés par jour sur le téléphone (le bridge en accepte 200 par appareil et par jour). */
  const val MAX_NAMES_PER_DAY = 120
  /** Jours gardés localement sans envoi possible (le bridge accepte 90 jours en arrière, on reste largement dedans). */
  const val KEEP_DAYS = 14L
  /** Largeur d'une tranche d'envoi, en heures (24 doit en être un multiple). */
  const val SLOT_HOURS = 6
  /** Pref : jour du premier démarrage de l'app avec ces prefs (absente = installation neuve ou données effacées). */
  const val PREF_SINCE = "usage_since"
  /** Compteurs relayés par la montre (`/aura/usage`) : nom court, valeur bornée par message. */
  private val WATCH_NAME = Regex("^[a-z0-9_]{1,20}$")
  const val WATCH_MAX_PER_MESSAGE = 100

  /** Tranche courante, `AAAA-MM-JJTn` (n = heure / 6) : la clé « déjà envoyé » de `due`. */
  fun slot(now: LocalDateTime): String = "${now.toLocalDate()}T${now.hour / SLOT_HOURS}"

  /**
   * Compteurs du démarrage à froid de l'app (module JS créé) : toujours `app.demarrage` ; `app.prefs_neuves` en plus quand
   * les prefs ne connaissent ni le repère des compteurs ni l'ancien repère du premier lancement (`asked_basics`, posé par
   * toutes les versions depuis 1.0.0) : une mise à jour ne compte donc pas comme une installation neuve.
   */
  fun coldStartCounters(hasSince: Boolean, hasLegacyMarker: Boolean): List<String> =
    if (hasSince || hasLegacyMarker) listOf("app.demarrage") else listOf("app.demarrage", "app.prefs_neuves")

  /**
   * `/aura/usage` de la montre : `{counters: {tuile: 1}}` -> `montre.tuile` += 1. Nom `[a-z0-9_]{1,20}`, valeur entière
   * 1..100 ; le reste est ignoré sans bruit (une montre plus récente ne fait jamais refuser le rapport du téléphone).
   */
  fun watchCounters(msg: JSONObject): List<Pair<String, Int>> {
    val c = msg.optJSONObject("counters") ?: return emptyList()
    val out = ArrayList<Pair<String, Int>>()
    for (k in c.keys()) {
      val v = c.opt(k)
      if (!WATCH_NAME.matches(k) || v !is Int || v < 1) continue
      out += "montre.$k" to v.coerceAtMost(WATCH_MAX_PER_MESSAGE)
    }
    return out.sortedBy { it.first }.take(10)
  }

  fun validName(name: String): Boolean = NAME.matches(name)

  /** Ajoute `n` à un compteur ; false si le nom est refusé (jamais d'exception : compter ne doit jamais casser l'app). */
  fun add(buckets: UsageBuckets, day: String, name: String, n: Int = 1): Boolean {
    if (n <= 0 || !validName(name)) return false
    val b = buckets.getOrPut(day) { LinkedHashMap() }
    if (name !in b && b.size >= MAX_NAMES_PER_DAY) return false
    b[name] = ((b[name] ?: 0).toLong() + n).coerceAtMost(MAX_VALUE.toLong()).toInt()
    return true
  }

  /** Un envoi est dû : rien n'est parti dans cette tranche (`slot`) et il y a quelque chose à dire. */
  fun due(lastReportSlot: String?, currentSlot: String, buckets: UsageBuckets): Boolean =
    lastReportSlot != currentSlot && buckets.values.any { it.isNotEmpty() }

  /** Supprime les jours trop vieux ou vides, rend le nombre de jours supprimés. */
  fun prune(buckets: UsageBuckets, today: LocalDate): Int {
    val oldest = today.minusDays(KEEP_DAYS).toString()  // AAAA-MM-JJ se compare comme du texte
    val gone = buckets.keys.filter { it < oldest || buckets[it].isNullOrEmpty() }
    gone.forEach { buckets.remove(it) }
    return gone.size
  }

  /** Nom d'appareil accepté par le bridge (1 à 40 caractères `[A-Za-z0-9_.-]`). */
  fun device(name: String?): String = (name ?: "").replace(DEVICE_BAD, "-").take(40).ifEmpty { "aura-android" }

  /**
   * Un `usage_report` par jour en attente, le plus ancien d'abord. Au plus 60 compteurs par rapport (les plus gros d'abord) ;
   * le reste attend. Les jours hors de la fenêtre du bridge (90 jours passés, demain au plus) ne sont jamais envoyés.
   */
  class Report(val day: String, val counters: LinkedHashMap<String, Int>, val message: JSONObject)

  fun reports(buckets: UsageBuckets, deviceName: String, today: LocalDate): List<Report> {
    val oldest = today.minusDays(89).toString()
    val newest = today.plusDays(1).toString()
    val out = ArrayList<Report>()
    for (day in buckets.keys.sorted()) {
      if (day < oldest || day > newest) continue
      val all = buckets[day] ?: continue
      val picked = LinkedHashMap<String, Int>()
      all.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(MAX_PER_REPORT).forEach { picked[it.key] = it.value.coerceIn(0, MAX_VALUE) }
      if (picked.isEmpty()) continue
      val counters = JSONObject()
      picked.forEach { (k, v) -> counters.put(k, v) }
      out += Report(day, picked, JSONObject().put("type", "usage_report").put("device", device(deviceName)).put("day", day).put("counters", counters))
    }
    return out
  }

  /** Les rapports d'un passage : les plus anciens d'abord, MAX_REPORTS_PER_PASS au plus (le bridge en refuse 6 par minute). */
  fun batch(all: List<Report>): List<Report> = all.take(MAX_REPORTS_PER_PASS)

  /** Retire de `buckets` ce qui vient de partir. */
  fun subtract(buckets: UsageBuckets, sent: List<Report>) {
    for (r in sent) {
      val b = buckets[r.day] ?: continue
      for ((k, v) in r.counters) {
        val left = (b[k] ?: 0) - v
        if (left > 0) b[k] = left else b.remove(k)
      }
      if (b.isEmpty()) buckets.remove(r.day)
    }
  }

  /** Remet ce qui est parti pour rien (bridge sans `usage_report`). */
  fun restore(buckets: UsageBuckets, sent: List<Report>) {
    for (r in sent) for ((k, v) in r.counters) add(buckets, r.day, k, v)
  }

  fun toJson(buckets: UsageBuckets): String {
    val o = JSONObject()
    for ((day, b) in buckets) {
      val d = JSONObject()
      b.forEach { (k, v) -> d.put(k, v) }
      o.put(day, d)
    }
    return o.toString()
  }

  /** Relit les prefs ; illisible = vide. Les noms et valeurs sont revalidés (un fichier bricolé ne fait pas refuser le rapport). */
  fun fromJson(raw: String?): UsageBuckets {
    val out = UsageBuckets()
    val o = try { JSONObject(raw ?: "{}") } catch (e: Exception) { return out }
    for (day in o.keys()) {
      val d = o.optJSONObject(day) ?: continue
      if (!Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(day)) continue
      for (k in d.keys()) {
        val v = d.opt(k)
        if (v is Int) add(out, day, k, v.coerceAtMost(MAX_VALUE))
      }
    }
    return out
  }
}
