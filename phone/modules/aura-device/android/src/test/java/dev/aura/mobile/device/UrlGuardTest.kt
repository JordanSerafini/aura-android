package dev.aura.mobile.device

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class UrlGuardTest {
  private fun codeOf(raw: String): String? = try {
    UrlGuard.webUrl(raw)
    null
  } catch (e: ActionError) {
    e.code
  }

  @Test fun http_et_https_passent_nettoyes() {
    assertEquals("https://exemple.fr/page?x=1#a", UrlGuard.webUrl("  https://exemple.fr/page?x=1#a \n"))
    assertEquals("http://192.168.1.10:8080/", UrlGuard.webUrl("http://192.168.1.10:8080/"))
    assertEquals("HTTPS://EXEMPLE.FR", UrlGuard.webUrl("HTTPS://EXEMPLE.FR"))
  }

  @Test fun les_autres_schemas_sont_refuses() {
    for (u in listOf("file:///data/data/dev.aura.mobile/shared_prefs/x.xml", "content://sms/inbox", "market://details?id=com.x",
      "tel:+33612345678", "sms:0612345678?body=x", "intent://scan/#Intent;scheme=zxing;end", "javascript:alert(1)",
      "data:text/html,<b>x</b>", "obsidian://open?vault=x", "aura://talk?autostart=1", "ftp://exemple.fr/x", "whatsapp://send?text=x")) {
      assertEquals(u, "bad_params", codeOf(u))
    }
  }

  @Test fun adresses_mal_formees_refusees() {
    for (u in listOf("", "   ", "https://", "https:///x", "http:// exemple.fr", "https://exemple.fr/a b", "https://exemple.fr/\u0000x",
      "https://exemple.fr/\nfile:///x", "exemple.fr", "//exemple.fr", "https:exemple.fr")) {
      assertEquals(u, "bad_params", codeOf(u))
    }
    assertEquals("bad_params", codeOf("https://exemple.fr/" + "a".repeat(UrlGuard.MAX_LEN)))
    if (codeOf("https://exemple.fr/" + "a".repeat(100)) != null) fail("une URL ordinaire doit passer")
  }
}
