package dev.aura.mobile.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class PauseLogicTest {
  private val utc = ZoneId.of("UTC")
  private val now = 1_790_000_000L

  // ─── Quelles actions sont refusees ──────────────────────────────────────

  @Test fun off_ne_refuse_rien() {
    for (a in listOf("screen_read", "ui_act", "sms_send", "device_status", "call")) assertFalse(a, PauseLogic.blocksAction(PauseMode.OFF, a))
    assertFalse(PauseLogic.blocksEvents(PauseMode.OFF))
    assertFalse(PauseLogic.blocksApps(PauseMode.OFF))
  }

  @Test fun apps_refuse_lecture_et_pilotage_seulement() {
    assertTrue(PauseLogic.blocksAction(PauseMode.APPS, "screen_read"))
    assertTrue(PauseLogic.blocksAction(PauseMode.APPS, "ui_act"))
    // tout le reste marche : appeler, ecrire un SMS, ouvrir une app, photo, etat
    for (a in listOf("sms_send", "call", "open_app", "camera_snap", "device_status", "notif_list", "message_send", "speak")) {
      assertFalse(a, PauseLogic.blocksAction(PauseMode.APPS, a))
    }
    assertFalse("apps laisse partir les evenements", PauseLogic.blocksEvents(PauseMode.APPS))
    assertTrue(PauseLogic.blocksApps(PauseMode.APPS))
  }

  @Test fun all_refuse_tout_sauf_device_status() {
    for (a in listOf("screen_read", "ui_act", "sms_send", "call", "open_app", "camera_snap", "notif_list", "flashlight", "speak",
      "watch_notify", "watch_heart_rate", "find_phone", "location_get", "action_inconnue")) {
      assertTrue(a, PauseLogic.blocksAction(PauseMode.ALL, a))
    }
    assertFalse(PauseLogic.blocksAction(PauseMode.ALL, "device_status"))
    assertTrue(PauseLogic.blocksEvents(PauseMode.ALL))
  }

  // Liste LITTERALE des actions du telephone (aiguillage d'ActionExecutor.run) et verdict attendu ecrit en dur :
  // la pause totale refuse tout sauf device_status, la pause « apps » seulement screen_read et ui_act.
  private val BLOCKED_IN_ALL = listOf(
    "sms_send", "sms_list", "call", "call_log", "contacts_search", "notif_list", "notif_reply", "notif_dismiss", "notif_open",
    "message_send", "calendar_list", "calendar_add", "alarm_set", "timer_set", "location_get", "volume_set", "dnd_set",
    "ringer_mode", "flashlight", "find_phone", "media_control", "media_now", "open_app", "open_url", "navigate",
    "clipboard_set", "clipboard_get", "email_compose", "speak", "camera_snap", "screen_read", "ui_act",
    "watch_notify", "watch_vibrate", "watch_heart_rate", "watch_steps", "watch_battery",
    "app_usage",  // 03/10 : lecture de l'usage, refusee en pause totale, permise en pause `apps` (rien n'est lu a l'ecran)
  )
  private val STAYS_ALLOWED = listOf("device_status")
  private val BLOCKED_IN_APPS = listOf("screen_read", "ui_act")

  @Test fun verdict_attendu_de_chaque_action_en_pause_totale() {
    for (a in BLOCKED_IN_ALL) assertTrue("$a doit etre refusee en pause totale", PauseLogic.blocksAction(PauseMode.ALL, a))
    for (a in STAYS_ALLOWED) {
      assertFalse("$a doit rester permise en pause totale", PauseLogic.blocksAction(PauseMode.ALL, a))
      assertFalse(PauseLogic.blocksAction(PauseMode.APPS, a))
      assertFalse(PauseLogic.blocksAction(PauseMode.OFF, a))
    }
  }

  @Test fun verdict_attendu_de_chaque_action_en_pause_apps_et_off() {
    for (a in BLOCKED_IN_ALL + STAYS_ALLOWED) {
      assertEquals("$a en apps", a in BLOCKED_IN_APPS, PauseLogic.blocksAction(PauseMode.APPS, a))
      assertFalse("$a hors pause", PauseLogic.blocksAction(PauseMode.OFF, a))
    }
  }

  @Test fun la_liste_litterale_couvre_l_aiguillage_du_code() {
    // une action ajoutee a ActionExecutor.run sans etre classee ici fait echouer ce test : on tranche alors a la main
    val file = java.io.File("src/main/java/dev/aura/mobile/device/ActionExecutor.kt")
    assertTrue("source introuvable depuis ${file.absoluteFile.parent} (les tests tournent dans le dossier du module)", file.exists())
    val src = file.readText()
    val dispatch = src.substringAfter("fun run(action: String").substringBefore("else -> throw ActionError(\"unsupported\"")
    val actions = Regex("^    \"([a-z_]+)\" ->", RegexOption.MULTILINE).findAll(dispatch).map { it.groupValues[1] }.toSet()
    assertTrue("aiguillage lu", actions.size > 30)
    assertEquals(actions, (BLOCKED_IN_ALL + STAYS_ALLOWED).toSet())
  }

  @Test fun refus_porte_le_code_paused_et_l_echeance() {
    val e = PauseLogic.refusal(PauseMode.APPS, now)
    assertEquals("paused", e.code)
    assertTrue(e.message!!.contains("jusqu'à"))
    assertTrue(PauseLogic.refusal(PauseMode.ALL, 0L).message!!.contains("jusqu'à la reprise"))
  }

  // ─── Etat, echeance ─────────────────────────────────────────────────────

  @Test fun pause_echue_vaut_off() {
    val st = PauseState(PauseMode.ALL, until = now + 60)
    assertEquals(PauseMode.ALL, st.effectiveMode(now))
    assertEquals(PauseMode.ALL, st.effectiveMode(now + 59))
    assertEquals(PauseMode.OFF, st.effectiveMode(now + 60))  // frontiere : l'echeance elle-meme est la reprise
    assertEquals(PauseMode.APPS, PauseState(PauseMode.APPS, 0L).effectiveMode(now + 10_000_000))  // sans echeance : jusqu'a la reprise
  }

  @Test fun normalize_coherent() {
    assertEquals(PauseMode.OFF to 0L, PauseLogic.normalize(PauseMode.OFF, now + 100, now))
    assertEquals(PauseMode.OFF to 0L, PauseLogic.normalize("n'importe quoi", 0, now))
    assertEquals(PauseMode.OFF to 0L, PauseLogic.normalize(PauseMode.ALL, now - 1, now))  // echeance deja passee
    assertEquals(PauseMode.OFF to 0L, PauseLogic.normalize(PauseMode.ALL, now, now))
    assertEquals(PauseMode.APPS to (now + 900), PauseLogic.normalize(PauseMode.APPS, now + 900, now))
    assertEquals(PauseMode.ALL to 0L, PauseLogic.normalize(PauseMode.ALL, -5, now))
  }

  @Test fun json_aller_retour_et_valeurs_illisibles() {
    val st = PauseState(PauseMode.APPS, now + 900, "montre", 1234L, pending = true)
    assertEquals(st, PauseState.fromJson(st.toJson().toString()))
    assertEquals(PauseState(), PauseState.fromJson(null))
    assertEquals(PauseState(), PauseState.fromJson("pas du json"))
    assertEquals(PauseMode.OFF, PauseState.fromJson("{\"mode\":\"bidon\"}").mode)  // jamais une pause devinee
  }

  @Test fun forme_du_protocole_est_l_etat_en_vigueur() {
    val live = PauseState(PauseMode.ALL, now + 60, "pc").toWire(now)
    assertEquals(PauseMode.ALL, live.getString("mode"))
    assertEquals(now + 60, live.getLong("until"))
    val dead = PauseState(PauseMode.ALL, now + 60, "pc").toWire(now + 61)
    assertEquals(PauseMode.OFF, dead.getString("mode"))
    assertEquals(0L, dead.getLong("until"))
  }

  // ─── Messages du bridge ─────────────────────────────────────────────────

  @Test fun pause_state_lu_et_mode_inconnu_ignore() {
    val st = PauseLogic.fromMessage(JSONObject("""{"type":"pause_state","mode":"apps","until":1790000900,"by":"montre"}"""))!!
    assertEquals(PauseMode.APPS, st.mode)
    assertEquals(1790000900L, st.until)
    assertEquals("montre", st.by)
    assertEquals(0L, PauseLogic.fromMessage(JSONObject("""{"mode":"all","until":null,"by":null}"""))!!.until)
    assertNull("mode absent", PauseLogic.fromMessage(JSONObject("""{"until":5}""")))
    assertNull("mode inconnu : on ne devine pas un arret d'urgence", PauseLogic.fromMessage(JSONObject("""{"mode":"stop"}""")))
  }

  @Test fun welcome_en_objet_ou_a_plat() {
    assertEquals(PauseMode.ALL, PauseLogic.fromWelcome(JSONObject("""{"pause":{"mode":"all","until":0,"by":"pc"}}"""))!!.mode)
    assertEquals(PauseMode.APPS, PauseLogic.fromWelcome(JSONObject("""{"pause_state":{"mode":"apps"}}"""))!!.mode)
    val flat = PauseLogic.fromWelcome(JSONObject("""{"pause_mode":"apps","pause_until":1790000900,"pause_by":"pc"}"""))!!
    assertEquals(1790000900L, flat.until)
    assertNull("welcome d'un bridge sans pause", PauseLogic.fromWelcome(JSONObject("""{"type":"welcome","device":"s22"}""")))
  }

  @Test fun estampille_distante_en_secondes_ou_ms() {
    assertEquals(1_790_000_000_000L, PauseLogic.remoteStampMs(JSONObject("""{"ts":1790000000}""")))
    assertEquals(1_790_000_000_500L, PauseLogic.remoteStampMs(JSONObject("""{"changed_at":1790000000500}""")))
    assertNull(PauseLogic.remoteStampMs(JSONObject("""{"mode":"off"}""")))
  }

  // ─── Dernier changement gagne ───────────────────────────────────────────

  @Test fun sans_changement_local_en_attente_le_bridge_fait_foi() {
    val local = PauseState(PauseMode.APPS, 0L, "app", changedAt = 5_000L, pending = false)
    assertEquals(PauseLogic.Decision.ADOPT_REMOTE, PauseLogic.reconcile(local, null))
    assertEquals(PauseLogic.Decision.ADOPT_REMOTE, PauseLogic.reconcile(local, 9_000L))
  }

  @Test fun changement_fait_hors_ligne_est_renvoye_au_bridge() {
    val local = PauseState(PauseMode.ALL, 0L, "app", changedAt = 5_000L, pending = true)
    assertEquals(PauseLogic.Decision.PUSH_LOCAL, PauseLogic.reconcile(local, null))   // pas d'estampille : le notre est le dernier connu
    assertEquals(PauseLogic.Decision.PUSH_LOCAL, PauseLogic.reconcile(local, 4_000L)) // bridge plus ancien
    assertEquals(PauseLogic.Decision.ADOPT_REMOTE, PauseLogic.reconcile(local, 6_000L)) // le bridge a change apres nous
  }

  @Test fun deux_etats_equivalents() {
    val a = PauseState(PauseMode.APPS, now + 60, "app")
    assertTrue(PauseLogic.sameAs(a, PauseState(PauseMode.APPS, now + 60, "pc"), now))   // `by` ignore
    assertFalse(PauseLogic.sameAs(a, PauseState(PauseMode.APPS, now + 120, "pc"), now))
    assertFalse(PauseLogic.sameAs(a, PauseState(PauseMode.ALL, now + 60, "pc"), now))
    assertTrue("deux off", PauseLogic.sameAs(PauseState(), PauseState(PauseMode.APPS, now - 5), now))  // la seconde est echue
  }

  // ─── Texte ──────────────────────────────────────────────────────────────

  @Test fun phrase_d_etat() {
    assertEquals("Aura travaille normalement", PauseLogic.describe(PauseState(), now, utc))
    val until = 1_790_000_000L + 900
    val hhmm = PauseLogic.hhmm(until, utc)
    assertEquals("En pause (apps) jusqu'à $hhmm", PauseLogic.describe(PauseState(PauseMode.APPS, until), now, utc))
    assertEquals("En pause totale jusqu'à la reprise", PauseLogic.describe(PauseState(PauseMode.ALL, 0L), now, utc))
    assertEquals("Aura travaille normalement", PauseLogic.describe(PauseState(PauseMode.ALL, now - 1), now, utc))
    assertNotNull(PauseLogic.DURATIONS_S.firstOrNull { it == 0L })
    assertEquals(listOf(900L, 3600L, 0L), PauseLogic.DURATIONS_S)
  }

  // ─── File hors ligne rejouee au welcome ─────────────────────────────────

  private val outbox = listOf(
    JSONObject("""{"type":"phone_event","kind":"missed_call","number":"0612345678","ts":1}"""),
    JSONObject("""{"type":"notif_action","action":"dismiss","key":"k1"}"""),
    JSONObject("""{"type":"phone_event","kind":"battery_low","percent":9,"ts":2}"""),
  )

  @Test fun pause_totale_purge_les_phone_event_de_la_file() {
    val (kept, purged) = PauseLogic.splitOutbox(outbox, PauseMode.ALL)
    assertEquals(listOf("notif_action"), kept.map { it.getString("type") })
    assertEquals(listOf("missed_call", "battery_low"), purged.map { it.getString("kind") })
  }

  @Test fun hors_pause_totale_la_file_est_rejouee_entiere() {
    for (m in listOf(PauseMode.OFF, PauseMode.APPS)) {
      val (kept, purged) = PauseLogic.splitOutbox(outbox, m)
      assertEquals(m, 3, kept.size)
      assertTrue(m, purged.isEmpty())
    }
  }

  // ─── Charges REELLES du bridge (desktop_bridge/pause.py, Pause.payload) ─────────────────────────

  // `until` et `changed_at` sont des FLOTTANTS en secondes (time.time()), `by` vaut "" et `until` / `since` null en off
  private val activeWelcome = JSONObject("""{"type":"welcome","device":"s22-natif","pause":{"mode":"apps","until":1790003600.0,"by":"s22-natif","since":1790000000.25,"changed_at":1790000000.25}}""")
  private val offNeverChanged = JSONObject("""{"type":"welcome","pause":{"mode":"off","until":null,"by":"","since":null,"changed_at":null}}""")
  private val offAfterExpiry = JSONObject("""{"type":"pause_state","mode":"off","until":null,"by":"","since":null,"changed_at":1790003600.0}""")

  @Test fun welcome_du_bridge_avec_until_flottant() {
    val st = PauseLogic.fromWelcome(activeWelcome)!!
    assertEquals(PauseMode.APPS, st.mode)
    assertEquals(1_790_003_600L, st.until)
    assertEquals("s22-natif", st.by)
    assertTrue(st.active(1_790_000_100L))
    assertFalse("l'echeance est la reprise", st.active(1_790_003_600L))
  }

  @Test fun changed_at_en_secondes_flottantes_devient_des_millisecondes() {
    val stamp = PauseLogic.remoteStampMs(activeWelcome.getJSONObject("pause"))!!
    assertEquals(1_790_000_000_250L, stamp)
    assertEquals(1_790_003_600_000L, PauseLogic.remoteStampMs(offAfterExpiry))
  }

  @Test fun pause_off_sans_changed_at_ni_until() {
    val st = PauseLogic.fromWelcome(offNeverChanged)!!
    assertEquals(PauseMode.OFF, st.mode)
    assertEquals(0L, st.until)
    assertEquals("", st.by)
    assertNull("changed_at null : pas d'estampille", PauseLogic.remoteStampMs(offNeverChanged.getJSONObject("pause")))
    // sans estampille, un changement local en attente l'emporte
    assertEquals(PauseLogic.Decision.PUSH_LOCAL, PauseLogic.reconcile(PauseState(PauseMode.ALL, 0L, "app", 1_790_000_000_000L, true), null))
  }

  @Test fun pause_state_de_reprise_a_l_echeance() {
    val st = PauseLogic.fromMessage(offAfterExpiry)!!
    assertEquals(PauseMode.OFF, st.mode)
    assertEquals(0L, st.until)
    assertEquals(1_790_003_600_000L, PauseLogic.remoteStampMs(offAfterExpiry))
  }

  @Test fun estampille_du_bridge_plus_recente_que_le_changement_local_hors_ligne() {
    val remote = PauseLogic.remoteStampMs(activeWelcome.getJSONObject("pause"))  // 1790000000250 ms
    assertEquals(PauseLogic.Decision.ADOPT_REMOTE, PauseLogic.reconcile(PauseState(PauseMode.ALL, 0L, "app", 1_789_999_990_000L, true), remote))
    assertEquals(PauseLogic.Decision.PUSH_LOCAL, PauseLogic.reconcile(PauseState(PauseMode.ALL, 0L, "app", 1_790_000_005_000L, true), remote))
  }

  @Test fun etat_du_bridge_et_etat_local_identiques_malgre_le_flottant() {
    val remote = PauseLogic.fromWelcome(activeWelcome)!!
    val local = PauseState(PauseMode.APPS, 1_790_003_600L, "app", 5L, pending = true)
    assertTrue(PauseLogic.sameAs(local, remote, 1_790_000_100L))
  }
}
