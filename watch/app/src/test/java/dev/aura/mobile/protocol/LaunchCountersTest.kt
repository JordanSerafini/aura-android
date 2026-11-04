package dev.aura.mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchCountersTest {
  @Test fun la_tuile_compte_montre_tuile() {
    assertEquals("tuile", LaunchCounters.counter(record = true, openConfirm = false, source = "tuile"))
    assertEquals("tuile", LaunchCounters.counter(record = false, openConfirm = true, source = "tuile"))
  }

  @Test fun la_complication_a_son_propre_compteur() {
    assertEquals("complication", LaunchCounters.counter(record = false, openConfirm = true, source = "complication"))
    assertEquals("complication", LaunchCounters.counter(record = false, openConfirm = false, source = "complication"))
  }

  @Test fun tuile_rendue_avant_0_2_0_sans_source() {
    assertEquals("tuile", LaunchCounters.counter(record = true, openConfirm = false, source = null))
    assertEquals("tuile", LaunchCounters.counter(record = false, openConfirm = true, source = null))
  }

  @Test fun lanceur_et_notification_ne_comptent_pas() {
    assertNull(LaunchCounters.counter(record = false, openConfirm = false, source = null))
    assertNull(LaunchCounters.counter(record = true, openConfirm = false, source = "bidon"))
  }

  @Test fun charge_utile_lue_par_le_telephone() {
    assertEquals("""{"counters":{"tuile":1}}""", String(LaunchCounters.payload("tuile"), Charsets.UTF_8))
    // le téléphone n'accepte que [a-z0-9_]{1,20} (UsageLogic.watchCounters)
    for (name in listOf(LaunchCounters.TILE, LaunchCounters.COMPLICATION)) {
      assertEquals(true, Regex("^[a-z0-9_]{1,20}$").matches(name))
    }
    assertEquals("/aura/usage", Paths.USAGE)
  }
}
