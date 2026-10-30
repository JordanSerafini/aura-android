package dev.aura.mobile.device

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Revue securite du 27/10, N5 : « Accepter » d'une demande d'accord ne se touche pas depuis l'ecran verrouille, et la
 * carte (texte du SMS, destinataire) n'y est pas lisible. Invariant de source : ConfirmManager est une classe Android.
 */
class ConfirmLockTest {
  private val src = File("src/main/java/dev/aura/mobile/device/ConfirmManager.kt").readText()
    .lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")

  @Test fun accepter_exige_le_deverrouillage() {
    assertTrue(Regex("""Action\.Builder\([^)]*"Accepter"[^)]*\)[\s\S]{0,120}\.setAuthenticationRequired\(true\)""").containsMatchIn(src))
    assertTrue("plus d'addAction(0, \"Accepter\", …) brut", !Regex("""addAction\(0,\s*"Accepter"""").containsMatchIn(src))
  }

  @Test fun la_carte_est_privee_sur_l_ecran_verrouille() {
    assertTrue(src.contains("setVisibility(NotificationCompat.VISIBILITY_PRIVATE)"))
    assertTrue(src.contains("setPublicVersion("))
  }
}
