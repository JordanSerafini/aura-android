package dev.aura.mobile.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Revue du 27/10 (N1) : les actions notif_* ignoraient la liste noire qu'appliquent screen_read, ui_act et les evenements
class NotifGuardTest {
  private fun codeOf(block: () -> Unit): String? = try {
    block()
    null
  } catch (e: ActionError) {
    e.code
  }

  @Test fun notification_d_une_app_de_la_liste_noire_refusee() {
    for (p in listOf("com.boursorama.android.clients", "com.google.android.apps.authenticator2", "com.x8bit.bitwarden",
      "com.android.systemui", "COM.REVOLUT.REVOLUT")) {
      assertEquals(p, "app_blocked", codeOf { UiGuard.checkNotif(p) })
    }
  }

  @Test fun notification_d_une_messagerie_permise() {
    for (p in listOf("com.whatsapp", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.google.android.apps.messaging")) {
      assertEquals(p, null, codeOf { UiGuard.checkNotif(p) })
    }
  }

  @Test fun le_listener_applique_la_liste_noire_a_la_liste_et_a_la_cle() {
    // AuraNotificationListener importe android : son cablage se verifie dans la source (meme motif que PauseLogicTest)
    val file = java.io.File("src/main/java/dev/aura/mobile/device/AuraNotificationListener.kt")
    assertTrue("source introuvable depuis ${file.absoluteFile.parent} (les tests tournent dans le dossier du module)", file.exists())
    val src = file.readText()
    val active = src.substringAfter("fun active(): List<Info>").substringBefore("fun find(")
    val find = src.substringAfter("fun find(key: String)").substringBefore("fun reply(")
    assertTrue("notif_list : active() filtre UiGuard.blocked", active.contains("UiGuard.blocked(it.packageName)"))
    assertTrue("notif_reply/open/dismiss : find() appelle UiGuard.checkNotif", find.contains("UiGuard.checkNotif(sbn.packageName)"))
  }
}
