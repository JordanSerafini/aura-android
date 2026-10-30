package dev.aura.mobile.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Revue securite du 27/10, N8 : clipboard_set ne se confirme que pour un texte de paiement (le remplacer est le piege du
 * collage) ; un envoi ordinaire depuis un PC (adresse, code, lien) passe sans carte. notif_dismiss reste hors du
 * plancher (fermeture du bruit en lot) et refuse une app de la liste noire.
 */
class ClipboardGuardTest {
  private fun clip(t: String) = JSONObject().put("text", t)

  @Test fun un_texte_de_paiement_demande_l_accord() {
    for (t in listOf("FR76 3000 6000 0112 3456 7890 189", "IBAN : DE89370400440532013000", "4970 1012 3456 7890",
        "0x52908400098527886E0F7030069857D2E4169EE7", "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq")) {
      assertTrue(t, UiGuard.needsConfirm("clipboard_set", clip(t)))
    }
  }

  @Test fun les_envois_ordinaires_passent_sans_carte() {
    for (t in listOf("Code portail 4512", "12 rue de la Paix 75002 Paris", "https://maps.google.com/?q=Paris",
        "Rendez-vous jeudi 14 h", "06 12 34 56 78")) {
      assertFalse(t, UiGuard.needsConfirm("clipboard_set", clip(t)))
    }
  }

  @Test fun classement_fige_de_clipboard_set_et_notif_dismiss() {
    assertFalse("clipboard_set" in UiGuard.ALWAYS_CONFIRM)
    assertFalse("notif_dismiss" in UiGuard.ALWAYS_CONFIRM)
    assertFalse(UiGuard.needsConfirm("notif_dismiss", JSONObject().put("key", "k")))
  }

  @Test fun le_resume_montre_ce_qui_sera_copie_et_sa_longueur() {
    assertEquals("Copier dans le presse-papiers : « FR76 3000 6000 » (14 car.)",
      ConfirmSummary.build("clipboard_set", clip("FR76 3000 6000")))
  }

  private fun src(name: String) = File("src/main/java/dev/aura/mobile/device/$name").readText()

  @Test fun le_resume_montre_en_entier_une_adresse_cachee_en_fin_de_long_texte() {
    val t = "Merci pour le repas de samedi, voici le detail des courses et du vin. ".repeat(3) + "Rembourse sur FR76 3000 6000 0112 3456 7890 189"
    val s = ConfirmSummary.clipboard(t)
    assertTrue(s, s.endsWith("; paiement : « FR76 3000 6000 0112 3456 7890 189 »"))
    assertEquals(listOf("0x52908400098527886E0F7030069857D2E4169EE7"), ClipGuard.paymentParts("ok 0x52908400098527886E0F7030069857D2E4169EE7"))
    assertEquals(emptyList<String>(), ClipGuard.paymentParts("Code portail 4512"))
  }

  @Test fun clipboard_set_est_borne_et_notif_dismiss_passe_par_la_liste_noire() {
    assertTrue(Regex(""""clipboard_set" -> \{[^\n]*ClipGuard\.CLIP_MAX""").containsMatchIn(src("ActionExecutor.kt")))
    val l = src("AuraNotificationListener.kt")
    assertTrue(Regex("""fun dismiss\(key: String\) \{\s*find\(key\)""").containsMatchIn(l))
    assertTrue(Regex("""fun find\(key: String\)[\s\S]{0,300}UiGuard\.checkNotif\(""").containsMatchIn(l))
  }
}
