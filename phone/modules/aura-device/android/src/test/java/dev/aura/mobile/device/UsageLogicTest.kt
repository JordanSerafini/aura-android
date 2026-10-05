package dev.aura.mobile.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class UsageLogicTest {
  private val today = LocalDate.of(2026, 10, 1)
  private fun buckets() = UsageBuckets()

  @Test fun noms_acceptes_comme_le_bridge() {
    for (ok in listOf("ecran.actions", "tuile.dicter", "notif.annuler_reprise", "evt.filtre.call_ringing", "a", "x".repeat(40)))
      assertTrue(ok, UsageLogic.validName(ok))
    for (bad in listOf("", "Ecran", "ecran actions", "écran", "x".repeat(41), "a-b", "a/b", "ecran.actions\n", "+33199001122"))
      assertFalse("« $bad » serait refuse par le bridge", UsageLogic.validName(bad))
  }

  @Test fun tous_les_noms_utilises_par_l_app_sont_valides() {
    val kinds = EventsWantedLogic.KNOWN
    val names = listOf("ecran.aura", "ecran.telephone", "ecran.actions", "ecran.montre", "ecran.reglages", "ecran.journal", "ecran.talk",
      "tuile.dicter", "notif.rappeler", "notif.tout_valider", "notif.annuler_reprise", "notif.copier", "time.confirme", "time.annule",
      "pause.activee", "pause.levee", "fiche_appel.affichee") +
      kinds.flatMap { listOf("evt.envoye.$it", "evt.filtre.$it", "evt.antispam.$it") }
    for (n in names) assertTrue("« $n » doit etre valide", UsageLogic.validName(n))
    assertTrue("assez peu de noms pour un seul rapport", names.size <= UsageLogic.MAX_PER_REPORT)
  }

  @Test fun compter_additionne_et_ignore_les_noms_invalides() {
    val b = buckets()
    assertTrue(UsageLogic.add(b, "2026-10-01", "ecran.actions"))
    assertTrue(UsageLogic.add(b, "2026-10-01", "ecran.actions", 2))
    assertFalse(UsageLogic.add(b, "2026-10-01", "Mauvais Nom"))
    assertFalse(UsageLogic.add(b, "2026-10-01", "ecran.actions", 0))
    assertEquals(3, b["2026-10-01"]!!["ecran.actions"])
    assertEquals(1, b["2026-10-01"]!!.size)
  }

  @Test fun valeur_plafonnee_a_100000() {
    val b = buckets()
    UsageLogic.add(b, "2026-10-01", "x", UsageLogic.MAX_VALUE)
    UsageLogic.add(b, "2026-10-01", "x", 5)
    assertEquals(UsageLogic.MAX_VALUE, b["2026-10-01"]!!["x"])
  }

  @Test fun noms_distincts_bornes_par_jour() {
    val b = buckets()
    for (i in 1..UsageLogic.MAX_NAMES_PER_DAY) assertTrue(UsageLogic.add(b, "2026-10-01", "n$i"))
    assertFalse(UsageLogic.add(b, "2026-10-01", "nouveau"))
    assertTrue("un nom deja connu continue de compter", UsageLogic.add(b, "2026-10-01", "n1"))
  }

  // ─── Au plus un envoi par jour ──────────────────────────────────────────

  @Test fun un_envoi_est_du_une_fois_par_jour_et_seulement_s_il_y_a_quelque_chose() {
    val b = buckets()
    assertFalse("rien a dire : aucun envoi", UsageLogic.due(null, "2026-10-01", b))
    UsageLogic.add(b, "2026-10-01", "ecran.actions")
    assertTrue(UsageLogic.due(null, "2026-10-01", b))
    assertTrue(UsageLogic.due("2026-09-30", "2026-10-01", b))
    assertFalse("deja parti aujourd'hui", UsageLogic.due("2026-10-01", "2026-10-01", b))
  }

  @Test fun apres_l_envoi_le_reste_de_la_journee_part_le_lendemain_sous_son_jour() {
    val b = buckets()
    UsageLogic.add(b, "2026-10-01", "ecran.actions", 3)
    val first = UsageLogic.reports(b, "s22-natif", today)
    UsageLogic.subtract(b, first)
    assertTrue(b.isEmpty())
    UsageLogic.add(b, "2026-10-01", "ecran.actions", 2)   // plus tard le meme jour : pas d'envoi (deja parti)
    UsageLogic.add(b, "2026-10-02", "ecran.reglages", 1)
    val next = UsageLogic.reports(b, "s22-natif", today.plusDays(1))
    assertEquals(listOf("2026-10-01", "2026-10-02"), next.map { it.day })   // increments : le bridge additionne par jour
    assertEquals(2, next[0].counters["ecran.actions"])
  }

  // ─── Forme du message ───────────────────────────────────────────────────

  @Test fun message_usage_report_conforme_au_bridge() {
    val b = buckets()
    UsageLogic.add(b, "2026-10-01", "jeu.ouvert", 2)
    UsageLogic.add(b, "2026-10-01", "ecran.actions", 5)
    val r = UsageLogic.reports(b, "s22-natif", today).single()
    val m = r.message
    assertEquals("usage_report", m.getString("type"))
    assertEquals("s22-natif", m.getString("device"))
    assertEquals("2026-10-01", m.getString("day"))
    val c = m.getJSONObject("counters")
    assertEquals(5, c.getInt("ecran.actions"))
    assertEquals(2, c.getInt("jeu.ouvert"))
    // des entiers, ni decimaux ni chaines
    for (k in c.keys()) assertTrue(c.get(k) is Int)
  }

  @Test fun jamais_de_contenu_dans_le_message() {
    val b = buckets()
    UsageLogic.add(b, "2026-10-01", "evt.envoye.missed_call", 1)
    UsageLogic.add(b, "2026-10-01", "+33199001122", 1)   // un numero n'est pas un nom valide : il n'entre jamais
    val text = UsageLogic.reports(b, "s22-natif", today).single().message.toString()
    assertFalse(text.contains("+33"))
    assertFalse(text.contains("199001122"))
  }

  @Test fun soixante_compteurs_au_plus_les_plus_gros_d_abord() {
    val b = buckets()
    for (i in 1..80) UsageLogic.add(b, "2026-10-01", "n$i", i)
    val r = UsageLogic.reports(b, "s22-natif", today).single()
    assertEquals(UsageLogic.MAX_PER_REPORT, r.counters.size)
    assertTrue(r.counters.containsKey("n80"))
    assertFalse(r.counters.containsKey("n1"))
    // le reste attend l'envoi suivant
    UsageLogic.subtract(b, listOf(r))
    assertEquals(20, b["2026-10-01"]!!.size)
  }

  @Test fun jours_hors_de_la_fenetre_du_bridge_ne_partent_pas() {
    val b = buckets()
    UsageLogic.add(b, "2026-06-01", "x")   // plus de 90 jours
    UsageLogic.add(b, "2026-10-03", "x")   // apres demain
    UsageLogic.add(b, "2026-10-02", "x")   // demain : horloge qui avance, accepte
    assertEquals(listOf("2026-10-02"), UsageLogic.reports(b, "s22-natif", today).map { it.day })
  }

  @Test fun nom_d_appareil_assaini() {
    assertEquals("s22-natif", UsageLogic.device("s22-natif"))
    assertEquals("aura-android", UsageLogic.device(null))
    assertEquals("aura-android", UsageLogic.device(""))
    assertEquals("Mon-S22", UsageLogic.device("Mon S22"))
    assertEquals(40, UsageLogic.device("x".repeat(100)).length)
  }

  // ─── Bridge sans usage_report, persistance, vieux jours ─────────────────

  @Test fun ce_qui_est_parti_pour_rien_est_remis() {
    val b = buckets()
    UsageLogic.add(b, "2026-10-01", "ecran.actions", 3)
    val sent = UsageLogic.reports(b, "s22-natif", today)
    UsageLogic.subtract(b, sent)
    assertTrue(b.isEmpty())
    UsageLogic.add(b, "2026-10-01", "ecran.actions", 1)   // compte entre-temps
    UsageLogic.restore(b, sent)
    assertEquals(4, b["2026-10-01"]!!["ecran.actions"])
  }

  @Test fun persistance_aller_retour_et_prefs_abimees() {
    val b = buckets()
    UsageLogic.add(b, "2026-10-01", "ecran.actions", 3)
    UsageLogic.add(b, "2026-10-01", "tuile.dicter", 1)
    assertEquals(b, UsageLogic.fromJson(UsageLogic.toJson(b)))
    assertTrue(UsageLogic.fromJson(null).isEmpty())
    assertTrue(UsageLogic.fromJson("pas du json").isEmpty())
    // un fichier bricole ne fait pas refuser le rapport entier par le bridge : noms et jours revalides
    val dirty = UsageLogic.fromJson("""{"2026-10-01":{"ok.nom":2,"Mauvais Nom":1,"texte":"abc"},"hier":{"a":1}}""")
    assertEquals(mapOf("ok.nom" to 2), dirty["2026-10-01"])
    assertFalse(dirty.containsKey("hier"))
  }

  @Test fun jours_trop_vieux_et_vides_sont_elagues() {
    val b = buckets()
    UsageLogic.add(b, "2026-09-01", "x")
    UsageLogic.add(b, "2026-09-25", "x")
    b["2026-09-30"] = LinkedHashMap()
    assertEquals(2, UsageLogic.prune(b, today))
    assertEquals(listOf("2026-09-25"), b.keys.toList())
  }
}
