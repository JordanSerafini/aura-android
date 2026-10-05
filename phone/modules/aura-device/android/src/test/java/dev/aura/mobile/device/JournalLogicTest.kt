package dev.aura.mobile.device

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalLogicTest {
  private fun ev(kind: String, vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }

  // ─── Resume des evenements ──────────────────────────────────────────────

  @Test fun resume_d_un_appel_manque() {
    assertEquals("Appel manqué : Maman", JournalLogic.eventSummary("missed_call", ev("missed_call", "name" to "Maman", "number" to "0612")))
    assertEquals("Appel manqué : 0612345678", JournalLogic.eventSummary("missed_call", ev("missed_call", "number" to "0612345678")))
    assertEquals("Appel manqué : numéro masqué", JournalLogic.eventSummary("missed_call", ev("missed_call")))
  }

  @Test fun resume_d_une_notification_ne_garde_ni_titre_ni_texte() {
    val f = ev("notification", "app" to "com.whatsapp", "app_name" to "WhatsApp", "title" to "Camille", "text" to "code 482913")
    val s = JournalLogic.eventSummary("notification", f)
    assertEquals("Notification : WhatsApp", s)
    val entry = JournalLogic.eventEntry("notification", f, JournalLogic.SENT)
    assertFalse("contenu de tiers dans le journal", entry.toString().contains("482913") || entry.toString().contains("Camille"))
  }

  @Test fun resume_des_autres_evenements() {
    assertEquals("Entrée dans la zone Maison", JournalLogic.eventSummary("zone", ev("zone", "transition" to "enter", "zone" to "Maison")))
    assertEquals("Sortie de la zone Travail", JournalLogic.eventSummary("zone", ev("zone", "transition" to "exit", "zone" to "Travail")))
    assertEquals("Batterie à 14 %", JournalLogic.eventSummary("battery_low", ev("battery_low", "percent" to 14)))
    assertEquals("Chargeur branché (80 %)", JournalLogic.eventSummary("charger", ev("charger", "connected" to true, "percent" to 80)))
    assertEquals("Chargeur débranché", JournalLogic.eventSummary("charger", ev("charger", "connected" to false)))
    // la sonnerie ne garde pas le numero : la fiche d'appel et ce qui l'identifie ne sont ecrits nulle part
    assertEquals("Appel entrant", JournalLogic.eventSummary("call_ringing", ev("call_ringing", "number" to "0612345678")))
    assertFalse(JournalLogic.eventEntry("call_ringing", ev("call_ringing", "number" to "0612345678"), JournalLogic.SENT).toString().contains("0612345678"))
  }

  @Test fun entree_d_evenement_porte_le_statut() {
    for (st in listOf(JournalLogic.SENT, JournalLogic.QUEUED, JournalLogic.OFFLINE, JournalLogic.FILTERED, JournalLogic.BLOCKED, JournalLogic.PAUSED)) {
      val e = JournalLogic.eventEntry("zone", ev("zone", "zone" to "Maison"), st, "pourquoi")
      assertEquals("event", e.getString("type"))
      assertEquals(st, e.getString("status"))
      assertEquals("pourquoi", e.getString("detail"))
    }
  }

  // ─── Actions ────────────────────────────────────────────────────────────

  @Test fun statut_d_une_action() {
    assertEquals("ok", JournalLogic.actionStatus(true, null))
    assertEquals("paused", JournalLogic.actionStatus(false, "paused"))
    assertEquals("blocked", JournalLogic.actionStatus(false, "app_blocked"))
    assertEquals("blocked", JournalLogic.actionStatus(false, "app_not_allowed"))
    assertEquals("refused", JournalLogic.actionStatus(false, "refused"))
    assertEquals("timeout", JournalLogic.actionStatus(false, "timeout"))
    assertEquals("failed", JournalLogic.actionStatus(false, "wrong_app"))
    assertEquals("failed", JournalLogic.actionStatus(false, null))
  }

  @Test fun entree_d_action_garde_les_champs_de_l_ancien_journal() {
    val e = JournalLogic.actionEntry("ui_act", "Faire défiler vers le bas dans WhatsApp", "bridge", false, true, null, 120, "WhatsApp", "scroll")
    assertEquals("action", e.getString("type"))
    for (k in listOf("action", "summary", "source", "confirm", "ok", "error", "ms")) assertTrue("champ $k", e.has(k))
    assertEquals("WhatsApp", e.getString("app"))
    assertEquals("scroll", e.getString("op"))
    assertEquals("ok", e.getString("status"))
    assertTrue(e.isNull("error"))
    assertEquals(200, JournalLogic.actionEntry("x", "a".repeat(500), "app", false, true, null, 1).getString("summary").length)
  }

  @Test fun action_sans_app_n_ecrit_pas_de_champ_vide() {
    val e = JournalLogic.actionEntry("flashlight", "test", "app", false, true, null, 5)
    assertFalse(e.has("app"))
    assertFalse(e.has("op"))
  }

  // ─── Liste bornee ───────────────────────────────────────────────────────

  @Test fun rotation_a_200_entrees_la_plus_recente_en_tete() {
    val s = JournalStore()
    for (i in 0 until 250) s.add(JSONObject().put("type", "event").put("n", i))
    assertEquals(200, s.size)
    val arr = s.toJson()
    assertEquals(249, arr.getJSONObject(0).getInt("n"))
    assertEquals(50, arr.getJSONObject(199).getInt("n"))
  }

  @Test fun aller_retour_par_le_stockage() {
    val s = JournalStore()
    s.add(JSONObject().put("type", "event").put("n", 1))
    s.add(JSONObject().put("type", "action").put("n", 2))
    val t = JournalStore()
    t.load(s.toJson().toString())
    assertEquals(2, t.size)
    assertEquals(2, t.toJson().getJSONObject(0).getInt("n"))
  }

  @Test fun stockage_illisible_donne_un_journal_vide() {
    val s = JournalStore()
    s.add(JSONObject().put("n", 1))
    s.load("pas du json")
    assertEquals(0, s.size)
    s.load(null)
    assertEquals(0, s.size)
  }

  @Test fun filtre_evenements_actions() {
    val s = JournalStore()
    s.add(JSONObject().put("type", "event").put("n", 1))
    s.add(JSONObject().put("type", "action").put("n", 2))
    s.add(JSONObject().put("type", "event").put("n", 3))
    assertEquals(listOf(3, 1), s.filtered("event").map { it.getInt("n") })
    assertEquals(listOf(2), s.filtered("action").map { it.getInt("n") })
    assertEquals(3, s.filtered(null).size)
  }

  @Test fun ancien_journal_des_actions_est_repris() {
    // forme d'avant le 01/10 : pas de type ni de statut
    val old = JSONArray().put(JSONObject().put("action", "call").put("summary", "Appeler Maman").put("source", "bridge")
      .put("confirm", true).put("ok", false).put("error", "refused").put("ms", 3000).put("ts", 5L))
    val s = JournalStore()
    s.load(old.toString(), fromLegacy = true)
    val e = s.toJson().getJSONObject(0)
    assertEquals("action", e.getString("type"))
    assertEquals("refused", e.getString("status"))
    assertEquals("Appeler Maman", e.getString("summary"))
    assertEquals(5L, e.getLong("ts"))
  }

  @Test fun les_entrees_deja_au_nouveau_format_ne_sont_pas_retouchees() {
    val e = JSONObject().put("type", "event").put("status", "sent").put("kind", "zone")
    assertEquals("event", JournalLogic.migrate(e).getString("type"))
  }

  // ─── Texte ecrit : jamais garde ─────────────────────────────────────────

  private fun p(json: String) = JSONObject(json)

  @Test fun sms_garde_le_type_de_destinataire_et_la_longueur_pas_le_texte() {
    val e = JournalLogic.actionEntry("sms_send", "SMS à Maman : code 482913 pour ta banque", "bridge", true, true, null, 40,
      params = p("""{"to":"Maman","text":"code 482913 pour ta banque"}"""))
    assertEquals("SMS à un contact (26 car.)", e.getString("summary"))
    assertFalse(e.toString().contains("482913"))
    assertFalse("pas le nom du destinataire", e.toString().contains("Maman"))
    assertTrue(e.getBoolean("masked"))
    assertEquals("SMS à un numéro (2 car.)",
      JournalLogic.actionEntry("sms_send", "x", "bridge", true, true, null, 1, params = p("""{"to":"+33 6 12 34 56 78","text":"ok"}""")).getString("summary"))
  }

  @Test fun message_reponse_mail_presse_papiers_et_saisie_ui_act() {
    fun summary(action: String, json: String, op: String? = null, app: String? = null) =
      JournalLogic.actionEntry(action, "résumé du bridge avec le texte secret", "bridge", true, true, null, 1, app, op, p(json)).getString("summary")
    assertEquals("Message whatsapp à un contact (5 car.)", summary("message_send", """{"app":"whatsapp","to":"Paul","text":"salut"}"""))
    assertEquals("Réponse à une notification (4 car.)", summary("notif_reply", """{"key":"k","text":"oui!"}"""))
    assertEquals("E-mail à une adresse (12 car.)", summary("email_compose", """{"to":"a@b.fr","subject":"Objet","body":"Bonjour"}"""))
    assertEquals("Copie dans le presse-papiers (6 car.)", summary("clipboard_set", """{"text":"azerty"}"""))
    assertEquals("Saisie dans com.whatsapp (9 car.)", summary("ui_act", """{"app":"com.whatsapp","op":"type","text":"mdp123456"}""", op = "type"))
    // la saisie est reconnue par les params meme sans geste rapporte par le service
    assertEquals("Saisie dans whatsapp (3 car.)", summary("ui_act", """{"app":"whatsapp","op":"type","text":"abc"}"""))
    for (s in listOf(summary("message_send", """{"app":"whatsapp","to":"Paul","text":"salut"}"""), summary("ui_act", """{"app":"x","op":"type","text":"secret"}"""))) {
      assertFalse(s, s.contains("secret") || s.contains("salut"))
    }
  }

  @Test fun les_gestes_sans_saisie_gardent_le_resume_du_bridge() {
    assertEquals("Faire défiler vers le bas dans WhatsApp",
      JournalLogic.actionEntry("ui_act", "Faire défiler vers le bas dans WhatsApp", "bridge", false, true, null, 1, "WhatsApp", "scroll",
        p("""{"app":"whatsapp","op":"scroll"}""")).getString("summary"))
    val e = JournalLogic.actionEntry("call", "Appeler Maman", "bridge", true, true, null, 1, params = p("""{"to":"Maman"}"""))
    assertEquals("Appeler Maman", e.getString("summary"))
    assertFalse(e.has("masked"))
  }

  @Test fun test_local_sans_parametres_garde_le_libelle_seul() {
    assertEquals("SMS", JournalLogic.redactedSummary("sms_send", "texte", null).substringBefore(" "))
    assertFalse(JournalLogic.redactedSummary("sms_send", "texte", null).contains("texte"))
  }

  @Test fun ancien_journal_est_nettoye_au_chargement() {
    val old = JSONArray()
      .put(JSONObject().put("type", "action").put("action", "sms_send").put("kind", "sms_send").put("summary", "SMS à Maman : code 482913").put("status", "ok"))
      .put(JSONObject().put("type", "action").put("action", "ui_act").put("kind", "ui_act").put("op", "type").put("summary", "Saisir « mdp » dans WhatsApp").put("status", "ok"))
      .put(JSONObject().put("type", "action").put("action", "call").put("kind", "call").put("summary", "Appeler Maman").put("status", "ok"))
      .put(JSONObject().put("type", "action").put("action", "sms_send").put("summary", "SMS à un contact (3 car.)").put("masked", true))
    val s = JournalStore()
    assertEquals(2, s.load(old.toString()))
    val arr = s.toJson()
    assertEquals("SMS (texte non conservé)", arr.getJSONObject(0).getString("summary"))
    assertEquals("Saisie dans une app (texte non conservé)", arr.getJSONObject(1).getString("summary"))
    assertEquals("Appeler Maman", arr.getJSONObject(2).getString("summary"))
    assertEquals("SMS à un contact (3 car.)", arr.getJSONObject(3).getString("summary"))
    assertFalse(arr.toString().contains("482913") || arr.toString().contains("mdp"))
    assertEquals("deuxieme chargement : plus rien a nettoyer", 0, s.load(arr.toString()))
  }

  // ─── Effacer, ecritures serialisees ─────────────────────────────────────

  @Test fun effacer_vide_la_liste_et_le_stockage() {
    val writes = mutableListOf<String>()
    val log = JournalLog({ writes += it })
    log.add(JSONObject().put("n", 1))
    log.add(JSONObject().put("n", 2))
    assertEquals(2, log.size())
    log.clear()
    assertEquals(0, log.size())
    assertEquals("[]", log.json())
    assertEquals("la liste vide est bien ecrite", "[]", writes.last())
  }

  @Test fun chargement_d_un_ancien_journal_reecrit_la_version_nettoyee() {
    val writes = mutableListOf<String>()
    val log = JournalLog({ writes += it })
    log.load(JSONArray().put(JSONObject().put("type", "action").put("action", "sms_send").put("summary", "SMS à Maman : secret")).toString())
    assertEquals(1, writes.size)
    assertFalse(writes[0].contains("secret"))
    log.load("[]")
    assertEquals("rien a nettoyer : pas d'ecriture inutile", 1, writes.size)
  }

  @Test fun ecritures_concurrentes_serialisees_sans_instantane_perime() {
    val writes = java.util.Collections.synchronizedList(mutableListOf<Int>())
    // l'ecriture traine un peu : sans verrou englobant, un autre thread s'intercalerait entre l'instantane et l'ecriture
    val log = JournalLog({ raw -> Thread.yield(); writes += JSONArray(raw).length() })
    val threads = (0 until 8).map { t -> Thread { repeat(40) { i -> log.add(JSONObject().put("n", t * 100 + i)) } } }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    assertEquals(320, writes.size)
    assertEquals("chaque instantane ecrit est plus recent que le precedent", writes.sorted(), writes.toList())
    assertEquals(200, writes.last())  // le dernier ecrit est l'etat final (borne a 200)
    assertEquals(200, JSONArray(log.json()).length())
  }
}
