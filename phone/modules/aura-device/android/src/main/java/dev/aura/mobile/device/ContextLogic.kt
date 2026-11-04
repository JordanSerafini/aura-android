package dev.aura.mobile.device

import org.json.JSONArray
import org.json.JSONObject

/** Logique pure de PhoneContext (aucun import Android : testee en JVM, ContextLogicTest). PhoneContext la delegue. */
object ContextLogic {
  const val MOVE_M = 300f
  const val LOW_BATTERY = 15

  /** Motif du changement notable, null s'il n'y en a pas (logique pure, testee en JVM). */
  fun notable(prev: JSONObject, cur: JSONObject): String? {
    // tz (03/10, mode voyage du serveur) : changer de fuseau en voyage est un changement notable
    for (k in listOf("charging", "network", "headphones", "dnd", "ringer", "tz")) {
      if (prev.opt(k)?.toString() != cur.opt(k)?.toString()) return if (k == "charging") "charging" else k
    }
    val pb = prev.optInt("battery", -1)
    val cb = cur.optInt("battery", -1)
    if (pb != cb && (pb / 10 != cb / 10 || (cb < LOW_BATTERY && pb != cb))) return "battery"
    val pe = prev.optJSONObject("next_event")?.toString()
    val ce = cur.optJSONObject("next_event")?.toString()
    if (pe != ce) return "next_event"
    val pl = prev.optJSONObject("location")
    val cl = cur.optJSONObject("location")
    if ((pl == null) != (cl == null)) return "location"
    if (pl != null && cl != null) {
      if (meters(pl.getDouble("lat"), pl.getDouble("lon"), cl.getDouble("lat"), cl.getDouble("lon")) > MOVE_M) return "location"
    }
    return null
  }

  /** Distance haversine en metres (pas Location.distanceBetween : testable en JVM). */
  fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2).let { it * it } +
      Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
    return 2 * r * Math.asin(Math.sqrt(a))
  }

  /** Fusion pure (testee en JVM) : remplace la zone de meme nom (casse ignoree), sinon l'ajoute (10 max). */
  fun mergeZone(existing: JSONArray, name: String, lat: Double, lon: Double, radius: Double): JSONArray {
    val out = JSONArray()
    for (i in 0 until existing.length()) {
      val z = existing.optJSONObject(i) ?: continue
      if (z.optString("name").trim().equals(name, ignoreCase = true)) continue
      // lat/lon absentes ou illisibles = NaN, et JSONObject.put(NaN) leve : une zone abimee ne doit pas faire echouer la fusion
      if (!z.optDouble("lat").isFinite() || !z.optDouble("lon").isFinite()) continue
      out.put(JSONObject().put("name", z.optString("name")).put("lat", z.optDouble("lat"))
        .put("lon", z.optDouble("lon")).put("radius_m", z.optDouble("radius_m", 150.0)))
    }
    val zone = JSONObject().put("name", name).put("lat", lat).put("lon", lon).put("radius_m", radius)
    val list = (0 until out.length()).map { out.getJSONObject(it) }.takeLast(9) + zone
    return JSONArray(list)
  }

  /** Rayon de la zone de ce nom (casse et espaces ignores), null si elle n'existe pas ou si son rayon est illisible. */
  fun radiusOf(existing: JSONArray, name: String): Double? {
    val key = name.trim().replace(Regex("\\s+"), " ")
    for (i in 0 until existing.length()) {
      val z = existing.optJSONObject(i) ?: continue
      if (!z.optString("name").trim().replace(Regex("\\s+"), " ").equals(key, ignoreCase = true)) continue
      val r = z.optDouble("radius_m", Double.NaN)
      return if (r.isNaN() || r < 50 || r > 5000) null else r
    }
    return null
  }

  /** Fusion pure (testee en JVM) : la liste sans la zone de ce nom (casse et espaces ignores), memes champs que mergeZone. */
  fun withoutZone(existing: JSONArray, name: String): JSONArray {
    val key = name.trim().replace(Regex("\\s+"), " ")
    val out = JSONArray()
    for (i in 0 until existing.length()) {
      val z = existing.optJSONObject(i) ?: continue
      if (z.optString("name").trim().replace(Regex("\\s+"), " ").equals(key, ignoreCase = true)) continue
      out.put(JSONObject().put("name", z.optString("name")).put("lat", z.optDouble("lat"))
        .put("lon", z.optDouble("lon")).put("radius_m", z.optDouble("radius_m", 150.0)))
    }
    return out
  }
}
