package dev.aura.mobile.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeUrlTest {
  private val default = "wss://example.invalid/ws"

  @Test fun release_n_accepte_que_wss() {
    assertTrue(BridgeUrl.accepted("wss://hote.exemple/ws", debuggable = false))
    assertTrue(BridgeUrl.accepted("WSS://hote.exemple:8443/ws?x=1", debuggable = false))
    assertTrue(BridgeUrl.accepted(" wss://hote.exemple/ws \n", debuggable = false))
    for (u in listOf("ws://hote.exemple/ws", "ws://192.168.1.10:8080/ws", "ws://localhost/ws", "http://hote.exemple/ws",
      "https://hote.exemple/ws", "ftp://hote.exemple", "wss://", "wss:///ws", "wss://hote exemple/ws", "hote.exemple/ws", "")) {
      assertFalse(u, BridgeUrl.accepted(u, debuggable = false))
    }
  }

  @Test fun debug_accepte_aussi_ws_pour_un_serveur_local() {
    assertTrue(BridgeUrl.accepted("ws://10.0.2.2:8080/ws", debuggable = true))
    assertTrue(BridgeUrl.accepted("wss://hote.exemple/ws", debuggable = true))
    assertFalse(BridgeUrl.accepted("http://10.0.2.2/ws", debuggable = true))
    assertFalse(BridgeUrl.accepted("ws://", debuggable = true))
  }

  @Test fun url_enregistree_en_clair_est_ignoree_en_release() {
    // une ancienne version a pu ecrire ws:// dans les prefs : en release on retombe sur la valeur par defaut
    assertEquals(default, BridgeUrl.effective("ws://hote.exemple/ws", default, debuggable = false))
    assertEquals(default, BridgeUrl.effective(null, default, debuggable = false))
    assertEquals(default, BridgeUrl.effective("", default, debuggable = true))
    assertEquals("wss://hote.exemple/ws", BridgeUrl.effective(" wss://hote.exemple/ws ", default, debuggable = false))
    assertEquals("ws://10.0.2.2:8080/ws", BridgeUrl.effective("ws://10.0.2.2:8080/ws", default, debuggable = true))
  }

  @Test fun la_valeur_par_defaut_est_elle_meme_acceptee() {
    assertTrue(BridgeUrl.accepted(Aura.DEFAULT_URL, debuggable = false))
  }
}
