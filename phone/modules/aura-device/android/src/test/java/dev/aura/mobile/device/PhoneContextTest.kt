package dev.aura.mobile.device

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneContextTest {
  private fun ctx(vararg kv: Pair<String, Any>) = JSONObject().apply {
    put("battery", 80); put("charging", false); put("network", "wifi"); put("headphones", false)
    put("dnd", false); put("ringer", "normal"); put("screen_on", true)
    kv.forEach { (k, v) -> put(k, v) }
  }

  @Test fun ecran_et_horodatage_ne_declenchent_rien() {
    assertNull(PhoneContext.notable(ctx(), ctx("screen_on" to false, "ts" to 123L)))
  }

  @Test fun branchement_et_reseau() {
    assertEquals("charging", PhoneContext.notable(ctx(), ctx("charging" to true)))
    assertEquals("network", PhoneContext.notable(ctx(), ctx("network" to "cellular")))
    assertEquals("headphones", PhoneContext.notable(ctx(), ctx("headphones" to true)))
  }

  @Test fun batterie_par_dizaine_puis_chaque_point_sous_15() {
    assertNull(PhoneContext.notable(ctx("battery" to 78), ctx("battery" to 71)))
    assertEquals("battery", PhoneContext.notable(ctx("battery" to 71), ctx("battery" to 69)))
    assertEquals("battery", PhoneContext.notable(ctx("battery" to 14), ctx("battery" to 13)))
  }

  @Test fun deplacement_de_plus_de_300m() {
    val a = JSONObject().put("lat", 45.7640).put("lon", 4.8357)  // Lyon
    val near = JSONObject().put("lat", 45.7648).put("lon", 4.8363)  // ~100 m
    val far = JSONObject().put("lat", 45.7748).put("lon", 4.8357)   // ~1,2 km
    assertNull(PhoneContext.notable(ctx("location" to a), ctx("location" to near)))
    assertEquals("location", PhoneContext.notable(ctx("location" to a), ctx("location" to far)))
    assertEquals(1200.0, PhoneContext.meters(45.7640, 4.8357, 45.7748, 4.8357), 30.0)
  }

  @Test fun zone_de_meme_nom_remplacee_les_autres_gardees() {
    val zones = JSONArray()
      .put(JSONObject().put("name", "Maison").put("lat", 1.0).put("lon", 2.0).put("radius_m", 200.0))
      .put(JSONObject().put("name", "Travail").put("lat", 3.0).put("lon", 4.0).put("radius_m", 150.0))
    val out = PhoneContext.mergeZone(zones, "maison", 5.0, 6.0, 150.0)
    assertEquals(2, out.length())
    assertEquals("Travail", out.getJSONObject(0).getString("name"))
    assertEquals("maison", out.getJSONObject(1).getString("name"))
    assertEquals(5.0, out.getJSONObject(1).getDouble("lat"), 0.0)
    assertEquals(3, PhoneContext.mergeZone(zones, "Salle", 0.0, 0.0, 100.0).length())
  }
}
