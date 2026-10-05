package dev.aura.mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTest {
  private val now = 1_790_000_000L
  private fun status(mode: String = "off", until: Long = 0L, confirms: Int = 0, bridge: String = "online", ageS: Long = 10,
                     missed: Int = 0, times: Int = 0) =
    AuraStatus(pause = PauseWire(mode, until, "pc"), confirms = confirms, bridge = bridge, updated = (now - ageS) * 1000,
      missed = missed, times = times)

  // ─── Lecture du DataItem ────────────────────────────────────────────────

  @Test fun status_lu_depuis_le_json_du_telephone() {
    val raw = """{"pause":{"mode":"apps","until":1790003600,"by":"montre"},"confirms":2,"bridge":"online","updated":5,"autre":1}"""
    val s = StatusLogic.parse(raw.toByteArray())!!
    assertEquals("apps", s.pause.mode)
    assertEquals(1_790_003_600L, s.pause.until)
    assertEquals(2, s.confirms)
    assertEquals("online", s.bridge)
  }

  @Test fun status_illisible_ou_vide_donne_null() {
    assertNull(StatusLogic.parse(null))
    assertNull(StatusLogic.parse(ByteArray(0)))
    assertNull(StatusLogic.parse("pas du json".toByteArray()))
  }

  @Test fun champs_absents_valent_les_valeurs_normales() {
    val s = StatusLogic.parse("{}".toByteArray())!!
    assertEquals("off", s.pause.mode)
    assertEquals(0, s.confirms)
  }

  // ─── Pause en vigueur ───────────────────────────────────────────────────

  @Test fun pause_echue_vaut_off_sans_attendre_le_telephone() {
    assertEquals("apps", StatusLogic.pauseMode(status("apps", now + 60), now))
    assertEquals("off", StatusLogic.pauseMode(status("apps", now + 60), now + 60))
    assertEquals("all", StatusLogic.pauseMode(status("all", 0L), now + 99_999))  // sans echeance : jusqu'a la reprise
    assertEquals("off", StatusLogic.pauseMode(null, now))
    assertEquals("off", StatusLogic.pauseMode(status("bidon", 0L), now))
  }

  @Test fun bouton_pause_bascule_apps_une_heure_puis_off() {
    val first = StatusLogic.togglePause(status(), now)
    assertEquals("apps", first.mode)
    assertEquals(now + 3600, first.until)
    val second = StatusLogic.togglePause(status("apps", now + 3600), now + 10)
    assertEquals("off", second.mode)
    assertNull(second.until)
    // une pause totale se reprend aussi d'un appui
    assertEquals("off", StatusLogic.togglePause(status("all", 0L), now).mode)
    // pause echue : le bouton remet une pause, il ne « reprend » pas ce qui est deja fini
    assertEquals("apps", StatusLogic.togglePause(status("apps", now - 1), now).mode)
  }

  @Test fun etat_optimiste_apres_envoi() {
    val on = StatusLogic.applied(status(confirms = 3), PauseSet("apps", now + 3600))
    assertEquals("apps", on.pause.mode)
    assertEquals(now + 3600, on.pause.until)
    assertEquals(3, on.confirms)  // le reste de l'etat est conserve
    val off = StatusLogic.applied(on, PauseSet("off", null))
    assertEquals("off", off.pause.mode)
    assertEquals(0L, off.pause.until)
    assertEquals("apps", StatusLogic.applied(null, PauseSet("apps", now + 60)).pause.mode)
  }

  @Test fun pause_set_sans_echeance_n_ecrit_pas_until() {
    val json = String(Protocol.encode(PauseSet("off", null)))
    assertFalse(json, json.contains("until"))
    assertTrue(String(Protocol.encode(PauseSet("apps", 5L))).contains("\"until\":5"))
  }

  // ─── Tuile ──────────────────────────────────────────────────────────────

  @Test fun tuile_connectee_sans_confirmation() {
    val t = StatusLogic.tile(status(), phoneReachable = true, nowS = now)
    assertEquals("Connecté", t.headline)
    assertEquals(StatusLogic.Tone.OK, t.tone)
    assertEquals(0, t.confirms)
    assertEquals("Pause", t.pauseButton)
    assertFalse(t.paused)
  }

  @Test fun tuile_en_pause_propose_de_reprendre() {
    val t = StatusLogic.tile(status("apps", now + 600), true, now)
    assertEquals("En pause (apps)", t.headline)
    assertEquals(StatusLogic.Tone.WARN, t.tone)
    assertEquals("Reprendre", t.pauseButton)
    assertEquals("En pause totale", StatusLogic.tile(status("all"), true, now).headline)
  }

  @Test fun tuile_compte_les_confirmations_en_attente() {
    assertEquals(3, StatusLogic.tile(status(confirms = 3), true, now).confirms)
    assertEquals(0, StatusLogic.tile(status(confirms = -4), true, now).confirms)
  }

  @Test fun tuile_priorite_telephone_puis_pause_puis_bridge() {
    assertEquals("Téléphone injoignable", StatusLogic.tile(status("apps"), phoneReachable = false, nowS = now).headline)
    assertEquals("PC hors ligne", StatusLogic.tile(status(bridge = "offline"), true, now).headline)
    assertEquals("Appareil refusé", StatusLogic.tile(status(bridge = "rejected"), true, now).headline)
    assertEquals("Connexion…", StatusLogic.tile(status(bridge = "connecting"), true, now).headline)
    assertEquals("Aura", StatusLogic.tile(null, null, now).headline)  // jamais d'etat recu : pas d'alarme inventee
    // pause echue : la tuile ne l'affiche plus
    assertEquals("Connecté", StatusLogic.tile(status("apps", now - 1), true, now).headline)
  }

  // ─── Complication ───────────────────────────────────────────────────────

  @Test fun complication_nombre_de_confirmations() {
    val c = StatusLogic.complication(status(confirms = 2), true, now)
    assertEquals("2", c.text)
    assertEquals("À valider", c.title)
    assertEquals("1 confirmation en attente", StatusLogic.complication(status(confirms = 1), true, now).description)
    assertEquals("99+", StatusLogic.complication(status(confirms = 250), true, now).text)
  }

  @Test fun complication_pause_prime_sur_le_reste() {
    val c = StatusLogic.complication(status("all", confirms = 5, bridge = "offline"), true, now)
    assertEquals("Pause", c.text)
    assertTrue(c.text.length <= 7)
  }

  @Test fun complication_etat_de_repos() {
    assertEquals("OK", StatusLogic.complication(status(), true, now).text)
    assertEquals("OK", StatusLogic.complication(status(), null, now).text)  // lien pas encore lu : l'etat frais fait foi
    assertEquals("Hors", StatusLogic.complication(status(bridge = "offline"), true, now).text)
    for (m in listOf(status(), status("apps"), status(confirms = 99), status(bridge = "rejected"), status(ageS = 99_999))) {
      assertTrue(StatusLogic.complication(m, true, now).text.length <= 7)
    }
  }

  // ─── Appels manques a rappeler et temps a valider (01/10 soir) ──────────

  @Test fun status_lit_les_compteurs_du_telephone() {
    val s = StatusLogic.parse("""{"confirms":0,"bridge":"online","updated":5,"missed":3,"times":2}""".toByteArray())!!
    assertEquals(3, s.missed)
    assertEquals(2, s.times)
  }

  @Test fun telephone_plus_ancien_sans_compteurs_vaut_zero() {
    val s = StatusLogic.parse("""{"confirms":1,"bridge":"online","updated":5}""".toByteArray())!!
    assertEquals(0, s.missed)
    assertEquals(0, s.times)
    assertEquals(0, StatusLogic.todo(s))
  }

  @Test fun complication_compte_les_appels_a_rappeler() {
    val c = StatusLogic.complication(status(missed = 3), true, now)
    assertEquals("3", c.text)
    assertEquals("À rappeler", c.title)
    assertEquals("3 appels à rappeler, ouvrir", c.description)
    assertEquals("1 appel à rappeler, ouvrir", StatusLogic.complication(status(missed = 1), true, now).description)
  }

  @Test fun complication_compte_les_temps_a_valider() {
    val c = StatusLogic.complication(status(times = 2), true, now)
    assertEquals("2", c.text)
    assertEquals("Temps", c.title)
    assertEquals("2 temps à valider, ouvrir", c.description)
  }

  @Test fun complication_additionne_appels_et_temps() {
    val c = StatusLogic.complication(status(missed = 2, times = 3), true, now)
    assertEquals("5", c.text)
    assertEquals("À traiter", c.title)
    assertEquals("2 appels à rappeler, 3 temps à valider, ouvrir", c.description)
    assertEquals("99+", StatusLogic.complication(status(missed = 80, times = 40), true, now).text)
  }

  @Test fun une_confirmation_en_attente_passe_avant_les_compteurs() {
    val c = StatusLogic.complication(status(confirms = 1, missed = 4, times = 4), true, now)
    assertEquals("1", c.text)
    assertEquals("À valider", c.title)
  }

  @Test fun la_pause_et_l_etat_inconnu_priment_sur_les_compteurs() {
    assertEquals("Pause", StatusLogic.complication(status("apps", missed = 2), true, now).text)
    assertEquals("?", StatusLogic.complication(status(missed = 2, ageS = 3600), true, now).text)  // jamais un compteur perime
    assertEquals("Hors", StatusLogic.complication(status(missed = 2), phoneReachable = false, nowS = now).text)
  }

  @Test fun le_pc_hors_ligne_ne_cache_pas_ce_qui_reste_a_faire() {
    assertEquals("2", StatusLogic.complication(status(bridge = "offline", missed = 2), true, now).text)
  }

  @Test fun compteurs_negatifs_ou_fous_ne_donnent_pas_de_nombre() {
    assertEquals("OK", StatusLogic.complication(status(missed = -3, times = -1), true, now).text)
    assertTrue(StatusLogic.complication(status(missed = 5000, times = 5000), true, now).text.length <= 7)
  }

  // ─── Etat perime et telephone injoignable ───────────────────────────────

  @Test fun complication_jamais_ok_sur_un_etat_perime() {
    assertEquals("OK", StatusLogic.complication(status(ageS = StatusLogic.STALE_S), true, now).text)       // frontiere : 5 min pile, encore frais
    assertEquals("?", StatusLogic.complication(status(ageS = StatusLogic.STALE_S + 1), true, now).text)
    assertEquals("?", StatusLogic.complication(status(ageS = 3 * 3600), true, now).text)
    assertEquals("?", StatusLogic.complication(null, true, now).text)  // jamais d'etat recu : pas de « OK » invente
    assertEquals("?", StatusLogic.complication(AuraStatus(), null, now).text)  // `updated` absent (0)
    // une confirmation en attente d'il y a une heure n'est plus une information
    assertEquals("?", StatusLogic.complication(status(confirms = 2, ageS = 3600), true, now).text)
  }

  @Test fun complication_telephone_injoignable() {
    val c = StatusLogic.complication(status(), phoneReachable = false, nowS = now)
    assertEquals("Hors", c.text)
    assertTrue(c.description.contains("injoignable"))
    assertEquals("Hors", StatusLogic.complication(status(confirms = 3), false, now).text)
    assertEquals("Hors", StatusLogic.complication(status("apps", now + 600), false, now).text)  // comme la tuile : injoignable prime
  }

  @Test fun tuile_jamais_connecte_sur_un_etat_perime() {
    assertEquals("Connecté", StatusLogic.tile(status(ageS = StatusLogic.STALE_S), true, now).headline)
    val t = StatusLogic.tile(status(ageS = StatusLogic.STALE_S + 1), true, now)
    assertEquals("État inconnu", t.headline)
    assertEquals(StatusLogic.Tone.DIM, t.tone)
    assertEquals("Téléphone injoignable", StatusLogic.tile(status(ageS = 3600), false, now).headline)
    // une pause posee reste affichee : c'est le cote prudent, et le bouton propose de reprendre
    assertEquals("En pause (apps)", StatusLogic.tile(status("apps", now + 600, ageS = 3600), true, now).headline)
  }

  @Test fun age_de_l_etat() {
    assertFalse(StatusLogic.stale(null, now))
    assertTrue(StatusLogic.stale(AuraStatus(), now))
    assertEquals(StatusLogic.STALE_S - 10 + 1, StatusLogic.staleInS(status(), now))
    assertNull(StatusLogic.staleInS(status(ageS = 99_999), now))
  }

  @Test fun etat_optimiste_ne_rajeunit_pas_le_reste() {
    val old = status(ageS = 3600)
    val next = StatusLogic.applied(old, PauseSet("apps", now + 3600))
    assertEquals(old.updated, next.updated)
    assertTrue(StatusLogic.stale(next, now))
  }

  // ─── Clic remanent de la tuile ──────────────────────────────────────────

  @Test fun clic_pause_traite_une_seule_fois() {
    val id = TileClicks.pauseId(1_790_000_000_123L)
    assertEquals(1_790_000_000_123L, TileClicks.pausePending(id, lastHandled = 0L))
    // meme id rejoue par un requestUpdate apres traitement : ignore
    assertNull(TileClicks.pausePending(id, lastHandled = 1_790_000_000_123L))
    // nouveau rendu, nouveau clic : traite
    assertEquals(1_790_000_005_000L, TileClicks.pausePending(TileClicks.pauseId(1_790_000_005_000L), 1_790_000_000_123L))
  }

  @Test fun clic_sans_pause_ou_illisible_est_ignore() {
    assertNull(TileClicks.pausePending(null, 0L))
    assertNull(TileClicks.pausePending("", 0L))
    assertNull(TileClicks.pausePending("talk", 0L))
    assertNull(TileClicks.pausePending("confirm", 0L))
    assertNull(TileClicks.pausePending("pause", 0L))        // ancien id sans nonce : jamais de bascule a l'aveugle
    assertNull(TileClicks.pausePending("pause:abc", 0L))
  }
}
