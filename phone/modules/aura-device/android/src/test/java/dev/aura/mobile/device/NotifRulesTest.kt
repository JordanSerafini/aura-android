package dev.aura.mobile.device

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notifications du 17/10 soir : libelles complets, `silent`, canal « Fins de tache », rattrapage de nuit, `replaces`. */
class NotifRulesTest {
  private fun n(id: String, ts: Double, extra: String = "", cat: String = "aura", sev: Int = 1) =
    JSONObject("""{"id":"$id","ts":$ts,"text":"notif $id","category":"$cat","severity":$sev$extra}""")

  // ─── (a) libelles ───────────────────────────────────────────────────────

  @Test fun les_categories_sans_libelle_en_ont_un() {
    val voulus = mapOf(
      "aura-missions" to "Mission", "aura-superviseur" to "Superviseur", "aura-rapport" to "Rapport",
      "aura-nouvelle-categorie" to "Nouvelle categorie",
    )
    voulus.forEach { (cat, label) -> assertEquals(cat, label, NotifRules.label(cat)) }
  }

  @Test fun les_anciens_libelles_ne_bougent_pas() {
    assertEquals("Tâche terminée", NotifRules.label("aura-fin"))
    assertEquals("AURA", NotifRules.label("notifications"))
    assertEquals("Santé", NotifRules.label("memoire"))
    assertEquals("Aura", NotifRules.label("aura"))
  }

  @Test fun toute_categorie_du_journal_a_un_libelle_propre() {
    // les categories courantes du serveur : aucune ne retombe sur le generique « Aura » (sauf `aura` elle-meme)
    val journal = listOf("aura-fin", "notifications", "memoire", "aura-missions", "aura-superviseur", "aura-mail", "aura-telephone",
      "aura", "aura-sonnerie", "media", "aura-temps", "night", "aura-conseil", "aura-agenda", "aura-appel",
      "aura-rapport", "aura-ticket", "info", "alerte", "aura-sante", "sante", "aura-tri")
    journal.filter { it != "aura" }.forEach { assertTrue(it, NotifRules.label(it) != "Aura") }
  }

  @Test fun une_categorie_aura_neuve_est_nommee_d_apres_elle_meme() {
    assertEquals("Budget", NotifRules.label("aura-budget"))
    assertEquals("Nuit blanche", NotifRules.label("aura-nuit-blanche"))
    assertEquals("Aura", NotifRules.label("inconnue"))
    assertEquals("Aura", NotifRules.label("aura-"))
  }

  // ─── (b) silent ─────────────────────────────────────────────────────────

  @Test fun silent_retire_la_sonnerie_meme_en_gravite_haute() {
    assertTrue(NotifRules.isAlert(2, false))
    assertTrue(NotifRules.isAlert(3, false))
    assertFalse(NotifRules.isAlert(3, true))
    assertFalse(NotifRules.isAlert(2, true))
    assertFalse(NotifRules.isAlert(1, false))
  }

  // ─── (c) canal ──────────────────────────────────────────────────────────

  @Test fun une_fin_de_tache_va_dans_son_canal_les_alertes_dans_le_leur() {
    fun ch(cat: String, alert: Boolean) = NotifRules.channel(cat, alert, "fin", "alertes", "infos")
    assertEquals("fin", ch("aura-fin", true))
    assertEquals("fin", ch("aura-fin", false))
    assertEquals("alertes", ch("aura-sante", true))
    assertEquals("infos", ch("aura-sante", false))
    assertEquals("infos", ch("notifications", false))
  }

  // ─── rattrapage de nuit ─────────────────────────────────────────────────

  @Test fun rattrapage_de_nuit_sonne_pour_une_urgence_seulement() {
    val now = 10_000.0
    val recent = JSONArray()
      .put(n("info", now - 60))
      .put(n("alerte", now - 50, sev = 2, cat = "aura-sante"))
      .put(n("urgence", now - 40, sev = 3, cat = "memoire"))
      .put(n("fin", now - 30, sev = 3, cat = "aura-fin"))
    val nuit = NotifCatchUp.select(recent, 0.0, emptySet(), now, nightCalm = true)
    assertEquals(listOf("info", "alerte", "urgence", "fin"), nuit.show.map { it.getString("id") })
    assertEquals(listOf(true, true, false, true), nuit.silent)
    val jour = NotifCatchUp.select(recent, 0.0, emptySet(), now)
    assertEquals(listOf(false, false, false, false), jour.silent)
  }

  @Test fun rattrapage_respecte_silent_et_les_30_min() {
    val now = 10_000.0
    val recent = JSONArray().put(n("a", now - 60, ""","silent":true""", sev = 2)).put(n("b", now - 2000, sev = 3))
    assertEquals(listOf(true, true), NotifCatchUp.select(recent, 0.0, emptySet(), now).silent)
  }

  // ─── lot de fins de tache ───────────────────────────────────────────────

  @Test fun un_lot_remplace_les_fins_deja_affichees_ids_bien_formes_seulement() {
    val re = Regex("^[A-Za-z0-9_-]{1,64}$")
    val msg = JSONObject("""{"replaces":["abc123","../x","", "ok_1"]}""")
    assertEquals(listOf("abc123", "ok_1"), NotifRules.replacedIds(msg, re))
    assertEquals(emptyList<String>(), NotifRules.replacedIds(JSONObject("""{}"""), re))
    val long = JSONArray().apply { for (i in 1..80) put("id$i") }
    assertEquals(50, NotifRules.replacedIds(JSONObject().put("replaces", long), re).size)
  }
}
