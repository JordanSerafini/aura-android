package dev.aura.mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Écran principal, complication et tuile de la montre 0.2.0 (17/10). */
class StatusScreenTest {
  private val paris = ZoneId.of("Europe/Paris")
  // vendredi 17/10/2026 14:00, heure de Paris
  private val now = ZonedDateTime.of(2026, 10, 17, 14, 0, 0, 0, paris).toEpochSecond()

  private fun status(mode: String = "off", until: Long = 0L, confirms: Int = 0, bridge: String = "online", ageS: Long = 10) =
    AuraStatus(pause = PauseWire(mode, until, "pc"), confirms = confirms, bridge = bridge, updated = (now - ageS) * 1000)

  private fun at(h: Int, m: Int, dayOffset: Long = 0) =
    ZonedDateTime.of(2026, 10, 17, h, m, 0, 0, paris).plusDays(dayOffset).toEpochSecond()

  // ─── Bandeau ────────────────────────────────────────────────────────────

  @Test fun rien_quand_tout_va_bien_ni_sans_etat_recu() {
    assertNull(StatusLogic.banner(status(), true, now, paris))
    assertNull(StatusLogic.banner(status(), null, now, paris))
    assertNull(StatusLogic.banner(null, null, now, paris))
  }

  @Test fun pause_avec_heure_de_fin() {
    val b = StatusLogic.banner(status("apps", at(18, 30)), true, now, paris)!!
    assertEquals("En pause (apps) jusqu'à 18:30", b.text)
    assertEquals(StatusLogic.Tone.WARN, b.tone)
    assertNull(b.detail)
    assertEquals("En pause totale jusqu'à la reprise", StatusLogic.banner(status("all"), true, now, paris)!!.text)
    assertEquals("En pause (apps) jusqu'à demain 09:00", StatusLogic.banner(status("apps", at(9, 0, 1)), true, now, paris)!!.text)
    assertEquals("En pause (apps) jusqu'au 20/10 09:00", StatusLogic.banner(status("apps", at(9, 0, 3)), true, now, paris)!!.text)
    // pause échue : plus de bandeau
    assertNull(StatusLogic.banner(status("apps", now - 1), true, now, paris))
  }

  @Test fun pause_et_pc_hors_ligne_les_deux_sont_dits() {
    val b = StatusLogic.banner(status("apps", at(18, 30), bridge = "offline"), true, now, paris)!!
    assertEquals("En pause (apps) jusqu'à 18:30", b.text)
    assertEquals("PC hors ligne", b.detail)
  }

  @Test fun libelles_du_lien_avec_le_pc() {
    assertEquals("PC hors ligne", StatusLogic.banner(status(bridge = "offline"), true, now, paris)!!.text)
    assertEquals("Connexion au PC…", StatusLogic.banner(status(bridge = "connecting"), true, now, paris)!!.text)
    assertEquals(StatusLogic.Tone.DIM, StatusLogic.banner(status(bridge = "connecting"), true, now, paris)!!.tone)
    assertEquals("Aura arrêtée sur le téléphone", StatusLogic.banner(status(bridge = "stopped"), true, now, paris)!!.text)
    assertEquals("Téléphone refusé par le PC", StatusLogic.banner(status(bridge = "rejected"), true, now, paris)!!.text)
    assertEquals("PC hors ligne", StatusLogic.banner(status(bridge = "inconnu"), true, now, paris)!!.text)
  }

  @Test fun telephone_injoignable_prime() {
    val b = StatusLogic.banner(status("apps", at(18, 30), bridge = "offline"), false, now, paris)!!
    assertEquals("Téléphone injoignable", b.text)
    assertEquals(StatusLogic.Tone.BAD, b.tone)
    assertEquals("Téléphone injoignable", StatusLogic.banner(null, false, now, paris)!!.text)
  }

  @Test fun etat_perime_dit_inconnu_jamais_l_ancien_etat() {
    assertNull(StatusLogic.banner(status(ageS = StatusLogic.STALE_S), true, now, paris))  // 5 min pile : encore frais
    val b = StatusLogic.banner(status(bridge = "offline", ageS = 12 * 60), true, now, paris)!!
    assertEquals("État inconnu", b.text)
    assertEquals("Pas de nouvelles du téléphone depuis 12 min", b.detail)
    assertEquals("Pas de nouvelles du téléphone depuis 3 h", StatusLogic.banner(status(ageS = 3 * 3600), true, now, paris)!!.detail)
    assertEquals("Pas de nouvelles du téléphone", StatusLogic.banner(AuraStatus(), true, now, paris)!!.detail)
    // pause posée + état périmé : la pause reste affichée (prudent), la précision dit l'âge
    val p = StatusLogic.banner(status("apps", at(18, 30), ageS = 3600), true, now, paris)!!
    assertEquals("En pause (apps) jusqu'à 18:30", p.text)
    assertEquals("Pas de nouvelles du téléphone depuis 60 min", p.detail)
  }

  // ─── Pastille « N à valider » ───────────────────────────────────────────

  @Test fun pastille_compte_les_demandes_et_s_ouvre_si_la_montre_en_connait_une() {
    assertNull(StatusLogic.pendingChip(status(), now, 0))
    val remoteOnly = StatusLogic.pendingChip(status(confirms = 2), now, 0)!!
    assertEquals("2 à valider", remoteOnly.label)
    assertFalse(remoteOnly.canOpen)
    assertEquals("Réponds sur le téléphone", remoteOnly.detail)
    val local = StatusLogic.pendingChip(status(confirms = 2), now, 1)!!
    assertEquals("2 à valider", local.label)
    assertTrue(local.canOpen)
    // le téléphone n'a pas encore republié : la demande vue par la montre compte quand même
    assertEquals("1 à valider", StatusLogic.pendingChip(status(), now, 1)!!.label)
    assertEquals("99+ à valider", StatusLogic.pendingChip(status(confirms = 500), now, 0)!!.label)
  }

  @Test fun pastille_ignore_un_compte_perime() {
    assertNull(StatusLogic.pendingChip(status(confirms = 3, ageS = 3600), now, 0))
    assertNull(StatusLogic.pendingChip(null, now, 0))
    assertEquals("1 à valider", StatusLogic.pendingChip(status(confirms = 3, ageS = 3600), now, 1)!!.label)
  }

  // ─── Complication : ouvrir la demande ───────────────────────────────────

  @Test fun complication_a_valider_ouvre_la_demande() {
    assertTrue(StatusLogic.complication(status(confirms = 2), true, now).openConfirm)
    assertFalse(StatusLogic.complication(status(), true, now).openConfirm)
    assertFalse(StatusLogic.complication(status("apps", confirms = 2), true, now).openConfirm)  // « Pause » affichée
  }

  // ─── Tuile : échec de « Pause » ─────────────────────────────────────────

  @Test fun tuile_dit_l_echec_de_la_pause_quelques_secondes() {
    val base = StatusLogic.tile(status(), true, now)
    val failedAt = now * 1000
    val shown = StatusLogic.tileAfterPauseFailure(base, failedAt, failedAt + 2_000)
    assertEquals("Échec, téléphone injoignable", shown.headline)
    assertEquals(StatusLogic.Tone.BAD, shown.tone)
    assertEquals("Pause", shown.pauseButton)  // rien d'optimiste : le bouton propose toujours la pause
    assertFalse(shown.paused)
    assertEquals(base, StatusLogic.tileAfterPauseFailure(base, failedAt, failedAt + StatusLogic.PAUSE_FAILED_SHOWN_MS))
    assertEquals(base, StatusLogic.tileAfterPauseFailure(base, 0L, failedAt))
  }

  @Test fun tuile_aura_arretee_sur_le_telephone() {
    assertEquals("Aura arrêtée", StatusLogic.tile(status(bridge = "stopped"), true, now).headline)
  }
}
