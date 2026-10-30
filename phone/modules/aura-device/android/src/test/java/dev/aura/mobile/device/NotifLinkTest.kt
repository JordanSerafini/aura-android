package dev.aura.mobile.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Revue securite du 27/10, F1 : seul un lien aura://notif scelle par CE telephone execute une action dans la PWA. */
class NotifLinkTest {
  private val key = ByteArray(32) { it.toByte() }
  private val other = ByteArray(32) { (it + 1).toByte() }

  @Test fun un_lien_scelle_par_le_telephone_est_reconnu() {
    val u = NotifLink.uri(key, "7ad617618602", "act")
    assertTrue(u, Regex("^aura://notif/7ad617618602/act\\?k=[0-9a-f]{32}$").matches(u))
    assertTrue(NotifLink.verify(key, u))
    assertTrue(NotifLink.verify(key, NotifLink.uri(key, "x_1-2", "ask")))
  }

  @Test fun un_lien_forge_ou_d_un_autre_telephone_est_refuse() {
    val u = NotifLink.uri(key, "7ad617618602", "act")
    assertFalse(NotifLink.verify(other, u))
    assertFalse(NotifLink.verify(key, "aura://notif/7ad617618602/act"))
    assertFalse(NotifLink.verify(key, u.replace("/act?", "/ask?")))  // le sceau d'« ask » ne vaut pas pour « act »
    assertFalse(NotifLink.verify(key, u.replace("7ad617618602", "7ad617618603")))
    assertFalse(NotifLink.verify(key, "$u&x=1"))
    assertFalse(NotifLink.verify(key, null))
  }

  @Test fun un_id_hors_forme_garde_le_lien_nu() {
    assertEquals("aura://notif/a b/act", NotifLink.uri(key, "a b", "act"))
  }

  @Test fun les_notifications_ne_fabriquent_plus_de_lien_nu() {
    val dir = "src/main/java/dev/aura/mobile/device"
    for (f in listOf("Notifs.kt")) {
      val src = File("$dir/$f").readText()
      assertFalse("$f : lien aura://notif/…/act|ask non scelle", Regex(""""aura://notif/\$\{?[A-Za-z.]+\}?/(act|ask)"""").containsMatchIn(src))
      assertTrue(f, src.contains("NotifLink.uri("))
    }
    assertTrue(File("$dir/AuraDeviceModule.kt").readText().contains("Function(\"notifLinkOk\")"))
  }
}
