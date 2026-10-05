package dev.aura.mobile.device

import org.json.JSONArray
import org.json.JSONObject

/*
 * Logique pure (sans Android) des declencheurs `phone_event` (docs/PROTOCOL.md §9.1) : testee en JVM
 * (PhoneEventsTest). PhoneEvents.kt en est la colle Android (observateur du journal d'appels, ecouteur de
 * notifications, SharedPreferences).
 */

/** Reglages des declencheurs, editables dans l'app (Reglages → Declencheurs). Tout est desactivable. */
class EventSettings(
  val enabled: Boolean = true,
  val missedCall: Boolean = true,
  val batteryLow: Boolean = true,
  val charger: Boolean = true,
  val zone: Boolean = true,
  val notification: Boolean = true,
  /** Appel entrant a la sonnerie (`call_ringing`) : le bridge repond par une carte d'appel. */
  val callRinging: Boolean = true,
  /** Notification retiree (`notif_removed`, 03/10) : paquet + raison (ouverte, balayee, annulee par l'app), jamais un contenu. */
  val notifRemoved: Boolean = true,
  /** Paquets dont les notifications remontent (titre et texte tronques). VIDE par defaut : rien ne remonte. */
  val notifApps: List<String> = emptyList(),
) {
  fun allows(kind: String): Boolean = enabled && when (kind) {
    "missed_call" -> missedCall
    "battery_low" -> batteryLow
    "charger" -> charger
    "zone" -> zone
    "notification" -> notification
    "call_ringing" -> callRinging
    "notif_removed" -> notifRemoved
    else -> false
  }

  fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("missed_call", missedCall)
    .put("battery_low", batteryLow).put("charger", charger).put("zone", zone).put("notification", notification)
    .put("call_ringing", callRinging).put("notif_removed", notifRemoved).put("notif_apps", JSONArray(notifApps))

  companion object {
    /** Relit un JSON (prefs ou JS) : champ absent = valeur par defaut ; paquets sensibles jamais retenus. */
    fun fromJson(raw: String?): EventSettings {
      val j = try { JSONObject(raw ?: "{}") } catch (e: Exception) { JSONObject() }
      return EventSettings(
        enabled = j.optBoolean("enabled", true), missedCall = j.optBoolean("missed_call", true),
        batteryLow = j.optBoolean("battery_low", true), charger = j.optBoolean("charger", true),
        zone = j.optBoolean("zone", true), notification = j.optBoolean("notification", true),
        callRinging = j.optBoolean("call_ringing", true),
        notifRemoved = j.optBoolean("notif_removed", true),
        notifApps = UiGuard.parseList(j.optJSONArray("notif_apps")),
      )
    }
  }
}

/**
 * Anti-spam cote app : dedoublonnage (meme evenement dans la fenetre de son kind) + limite de debit par kind et
 * globale. `allow` enregistre l'evenement s'il passe ; un refus ne consomme rien. Le bridge a le sien
 * (phone_events.EventGate) : les deux se gardent independamment.
 *
 * `notif_removed` (03/10) n'entre PAS dans la limite globale : un « tout effacer » de 20 notifications ne doit jamais
 * empecher la sonnerie suivante (`call_ringing`) ou un appel manque de partir.
 */
class EventGate(
  private val dedupeMs: Map<String, Long> = DEDUPE_MS,
  private val limits: Map<String, Pair<Int, Long>> = LIMITS,
  private val global: Pair<Int, Long> = GLOBAL,
) {
  companion object {
    val DEDUPE_MS = mapOf("missed_call" to 300_000L, "notification" to 120_000L, "zone" to 600_000L,
      "battery_low" to 1_800_000L, "charger" to 60_000L, "call_ringing" to 60_000L, "notif_removed" to 60_000L)
    /** (nombre max, fenetre ms) : un peu plus strict que le bridge (10 notifications / min chez lui). */
    val LIMITS = mapOf("missed_call" to (5 to 600_000L), "notification" to (8 to 60_000L), "zone" to (4 to 600_000L),
      "battery_low" to (2 to 3_600_000L), "charger" to (6 to 600_000L),
      "call_ringing" to (5 to 600_000L), "notif_removed" to (20 to 60_000L))
    val GLOBAL = 40 to 600_000L
    /** Hors limite globale et hors `events_wanted` : alimentent le journal du bridge (phone_watch), pas des regles. */
    val JOURNAL_KINDS = setOf("notif_removed")
  }

  private val seen = HashMap<String, Long>()
  private val perKind = HashMap<String, ArrayDeque<Long>>()
  private val all = ArrayDeque<Long>()

  private fun count(q: ArrayDeque<Long>, window: Long, now: Long): Int {
    while (q.isNotEmpty() && now - q.first() >= window) q.removeFirst()
    return q.size
  }

  @Synchronized fun allow(kind: String, fingerprint: String, now: Long): Boolean {
    val window = dedupeMs[kind] ?: return false
    val key = "$kind|$fingerprint"
    val last = seen[key]
    if (last != null && now - last < window) return false
    val (n, win) = limits[kind] ?: return false
    val q = perKind.getOrPut(kind) { ArrayDeque() }
    val counted = kind !in JOURNAL_KINDS
    if (count(q, win, now) >= n || (counted && count(all, global.second, now) >= global.first)) return false
    q.addLast(now)
    if (counted) all.addLast(now)
    seen[key] = now
    if (seen.size > 300) {
      val horizon = dedupeMs.values.max()
      seen.entries.removeAll { now - it.value >= horizon }
    }
    return true
  }
}

/** Batterie basse « une fois par descente » : arme au depart, se desarme a l'envoi, se rearme a la charge ou a 20 %. */
class BatteryLatch(var armed: Boolean = true) {
  companion object {
    const val LOW = PhoneContext.LOW_BATTERY  // 15 %
    const val REARM = 20
  }

  /** true = envoyer maintenant. */
  fun step(percent: Int, charging: Boolean): Boolean {
    if (charging || percent >= REARM) {
      armed = true
      return false
    }
    if (armed && percent < LOW) {
      armed = false
      return true
    }
    return false
  }
}

/**
 * Entree / sortie de zone (`phone_zones`, §7.3) a partir de la derniere position connue. Pas de geofencing :
 * la position vient du contexte du telephone (toutes les 5 min au plus, derniere position connue, GPS jamais
 * allume pour ca). Donc un delai de quelques minutes, et rien tant que la position est vieille ou imprecise.
 * Hysteresis : on entre a `rayon`, on ne sort qu'a `rayon x 1,25` (sinon une position au bord clignoterait).
 */
object ZoneTracker {
  const val EXIT_FACTOR = 1.25
  const val MAX_AGE_MS = 20 * 60_000L
  const val MAX_ACCURACY_M = 200.0

  class Zone(val name: String, val lat: Double, val lon: Double, val radiusM: Double)

  /** inside = zones dont on est dedans APRES ce pas ; events = ("enter" | "exit", nom de zone). */
  class Step(val inside: Set<String>, val events: List<Pair<String, String>>)

  fun parseZones(arr: JSONArray?): List<Zone> = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
    val z = arr?.optJSONObject(i) ?: return@mapNotNull null
    val name = z.optString("name").trim()
    if (name.isEmpty() || !z.has("lat") || !z.has("lon")) null
    else Zone(name, z.optDouble("lat"), z.optDouble("lon"), z.optDouble("radius_m", 150.0))
  }

  /**
   * prev null = premiere observation : l'etat est appris, aucun evenement (sinon « arrive a la maison » au premier
   * lancement). null en retour = position inexploitable (vieille, imprecise) : l'etat ne bouge pas.
   */
  fun step(prev: Set<String>?, lat: Double, lon: Double, accuracy: Double, fixTs: Long, zones: List<Zone>, now: Long): Step? {
    if (now - fixTs > MAX_AGE_MS || accuracy > MAX_ACCURACY_M) return null
    val inside = mutableSetOf<String>()
    val events = mutableListOf<Pair<String, String>>()
    for (z in zones) {
      val d = PhoneContext.meters(lat, lon, z.lat, z.lon)
      val was = prev != null && z.name in prev
      val now2 = if (was) d <= z.radiusM * EXIT_FACTOR else d <= z.radiusM
      if (now2) inside += z.name
      if (prev != null && now2 != was) events += (if (now2) "enter" else "exit") to z.name
    }
    return Step(inside, events)
  }
}

/** Texte de tiers remonte au bridge : une ligne, sans caracteres de controle, tronque (defense en profondeur). */
fun clipEventText(s: CharSequence?, max: Int): String {
  val one = (s?.toString() ?: "").replace(Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u2028\\u2029]+"), " ")
    .trim().replace(Regex("\\s+"), " ")
  return if (one.length <= max) one else one.take(max - 1) + "…"
}

/**
 * Notification retiree (`notif_removed`, PROTOCOL.md §19.3) : logique pure de `onNotificationRemoved(sbn, rankingMap,
 * reason)`. Ce qui part : le paquet, la raison, la categorie Android, un canal « technique », effacable ou non, l'age de la
 * notification et une empreinte de sa cle (la cle d'une notification WhatsApp contient le numero : jamais en clair).
 */
object NotifRemoval {
  /**
   * Raisons d'Android (NotificationListenerService.REASON_*, valeurs fixes de l'API 26+) -> nom du protocole.
   * Absentes de la table (resume de groupe annule, regroupement automatique, paquet modifie, profil coupe, donnees
   * effacees...) : bruit de mecanique Android, rien ne part.
   */
  private val REASONS = mapOf(
    1 to "click",            // ouverte (touchee)
    2 to "cancel",           // balayee
    3 to "cancel_all",       // « Tout effacer »
    8 to "app_cancel",       // retiree par l'app (lue ailleurs, conversation ouverte sur le PC...)
    9 to "app_cancel_all",
    10 to "listener_cancel", // fermee par un ecouteur (Aura : notif_dismiss)
    11 to "listener_cancel",
    17 to "channel_banned",  // canal coupe par l'utilisateur
    7 to "package_banned",   // notifications de l'app coupees
    18 to "snoozed",
    19 to "timeout",
  )
  val NAMES: Set<String> = REASONS.values.toSet()
  private val CATEGORY = Regex("^[a-z_]{1,24}$")
  private val CHANNEL = Regex("^[A-Za-z0-9_.:-]{1,40}$")
  private val DIGITS = Regex("[0-9]{6,}")

  fun reason(code: Int): String? = REASONS[code]

  /** Categorie Android (`msg`, `call`, `email`, `promo`, `social`, `transport` = lecteur multimedia...) ou null. */
  fun category(raw: String?): String? = raw?.takeIf { CATEGORY.matches(it) }

  /** Identifiant de canal seulement s'il a l'allure d'un identifiant (un canal de conversation peut porter un numero). */
  fun channel(raw: String?): String? = raw?.takeIf { CHANNEL.matches(it) && !DIGITS.containsMatchIn(it) }

  /** Empreinte courte de la cle (SHA-256, 12 hex) : dedoublonne sans rien reveler. */
  fun nid(key: String): String {
    val d = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
    return d.take(6).joinToString("") { "%02x".format(it) }
  }

  /** Champs de l'evenement ; null = rien a envoyer (raison de mecanique). `postTime`/`now` en ms. */
  fun fields(app: String, appName: String, reasonCode: Int, category: String?, channel: String?, clearable: Boolean,
             postTime: Long, key: String, now: Long): JSONObject? {
    val r = reason(reasonCode) ?: return null
    val f = JSONObject().put("app", app).put("app_name", clipEventText(appName, 60)).put("reason", r)
      .put("clearable", clearable).put("nid", nid(key))
    category(category)?.let { f.put("category", it) }
    channel(channel)?.let { f.put("channel", it) }
    if (postTime in 1..now) f.put("age_s", (now - postTime) / 1000)
    return f
  }
}
