package dev.aura.mobile.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RelayTest {
  class FakeIo : RelayIo {
    var isOnline = true
    val sent = mutableListOf<JSONObject>()
    var clock = 0L
    val timers = mutableListOf<Pair<Long, () -> Unit>>()
    override fun online() = isOnline
    override fun rejected() = false
    override fun send(obj: JSONObject): Boolean { sent += JSONObject(obj.toString()); return isOnline }
    override fun deviceName() = "s22-natif"
    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
      val t = (clock + delayMs) to task
      timers += t
      return { timers.remove(t) }
    }
    override fun async(task: () -> Unit) = task()
    override fun sleep(ms: Long) { clock += ms }
    override fun now() = clock
    fun advance(ms: Long) {
      clock += ms
      timers.filter { it.first <= clock }.forEach { timers.remove(it); it.second() }
    }
    fun types() = sent.map { it.getString("type") }
  }

  class Sink : RelaySink {
    val states = mutableListOf<String>()
    val audio = mutableListOf<Pair<Int, String>>()
    var transcript = ""
    var reply = ""
    var finalReply = ""
    var failed: String? = null
    var conv: String? = null
    override fun state(state: String, text: String?) { states += state }
    override fun transcript(text: String) { transcript = text }
    override fun reply(text: String, final: Boolean) { reply = text; if (final) finalReply = text }
    override fun audio(seq: Int, mp3: ByteArray, last: Boolean, text: String?) { audio += seq to String(mp3) + (if (last) "|last" else "") }
    override fun audioFailed(message: String) { failed = message }
    override fun conv(id: String) { conv = id }
  }

  private fun voice(id: String) = JSONObject().put("type", "voice").put("id", id).put("audio", "AAAA").put("origin", "talk")
  private fun msg(s: String) = JSONObject(s)
  private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

  @Test fun parcours_complet_voix_en_flux() {
    val io = FakeIo(); val r = Relay(io); val sink = Sink()
    r.submitNow(Relay.Req("t1", voice("t1"), sink, speak = true))
    assertEquals(true, io.sent[0].getBoolean("speak"))
    assertEquals("talk", io.sent[0].getString("origin"))
    r.onBridge(msg("""{"type":"delta","conv":"c1","id":"t1","text":"","tool":"transcription"}"""))
    r.onBridge(msg("""{"type":"message","conv":"c1","message":{"role":"me","text":"🎙️ quelle heure ?","id":"t1"}}"""))
    r.onBridge(msg("""{"type":"delta","conv":"c1","id":"t1","text":"Il est ","tool":null}"""))
    r.onBridge(msg("""{"type":"tts_audio","id":"t1","seq":0,"last":false,"audio":"${b64("A")}","mime":"audio/mpeg","text":"Il est 14 h."}"""))
    r.onBridge(msg("""{"type":"delta","conv":"c1","id":"t1","text":"14 h.","tool":null}"""))
    r.onBridge(msg("""{"type":"done","conv":"c1","id":"t1","text":"Il est 14 h.","error":null}"""))
    r.onBridge(msg("""{"type":"tts_audio","id":"t1","seq":1,"last":true,"audio":"","mime":"audio/mpeg"}"""))
    assertEquals(listOf("sent", "transcribing", "thinking", "talking", "done"), sink.states)
    assertEquals("quelle heure ?", sink.transcript)
    assertEquals("Il est 14 h.", sink.finalReply)
    assertEquals("c1", sink.conv)
    assertEquals(listOf(0 to "A", 1 to "|last"), sink.audio)
    io.advance(5000)
    assertFalse("pas de repli tts : l'audio est venu", io.types().contains("tts"))
    assertFalse(r.active("t1"))
  }

  @Test fun repli_tts_3s_apres_done_sans_audio() {
    // bridge d'avant le §7.1 : `speak` ignore, aucun tts_audio spontane
    val io = FakeIo(); val r = Relay(io); val sink = Sink()
    r.submitNow(Relay.Req("t2", voice("t2"), sink, speak = true))
    r.onBridge(msg("""{"type":"done","conv":"c1","id":"t2","text":"Bonjour.","error":null}"""))
    io.advance(2900)
    assertFalse(io.types().contains("tts"))
    io.advance(200)
    val tts = io.sent.last()
    assertEquals("tts", tts.getString("type"))
    assertEquals("t2.tts", tts.getString("id"))
    assertEquals("Bonjour.", tts.getString("text"))
    // un tts_audio tardif du flux (id de la requete) est ignore une fois le repli lance (PROTOCOL.md §7.5)
    r.onBridge(msg("""{"type":"tts_audio","id":"t2","seq":0,"last":false,"audio":"${b64("X")}","mime":"audio/mpeg"}"""))
    // la voix du repli arrive sous son propre id
    r.onBridge(msg("""{"type":"tts_audio","id":"t2.tts","seq":0,"last":true,"audio":"${b64("V")}","mime":"audio/mpeg"}"""))
    assertEquals(listOf(0 to "V|last"), sink.audio)
    io.advance(10_000)
    assertEquals(1, io.types().count { it == "tts" })
  }

  @Test fun tts_error_en_flux_laisse_le_repli_tenter() {
    val io = FakeIo(); val r = Relay(io); val sink = Sink()
    r.submitNow(Relay.Req("t3", voice("t3"), sink, speak = true))
    r.onBridge(msg("""{"type":"tts_error","id":"t3","message":"edge-tts HS"}"""))
    assertNull(sink.failed)
    r.onBridge(msg("""{"type":"done","conv":"c1","id":"t3","text":"Ok.","error":null}"""))
    io.advance(3100)
    assertEquals("tts", io.sent.last().getString("type"))
    r.onBridge(msg("""{"type":"tts_error","id":"t3.tts","message":"toujours HS"}"""))
    assertEquals("toujours HS", sink.failed)
  }

  @Test fun busy_ouvre_une_conversation_et_renvoie() {
    val io = FakeIo(); val r = Relay(io); val sink = Sink()
    r.submitNow(Relay.Req("t4", voice("t4"), sink, speak = true))
    r.onError(msg("""{"type":"error","code":"busy","message":"Je réponds déjà","conv":"c1","id":"t4"}"""))
    assertEquals("conv_new", io.sent.last().getString("type"))
    r.onBridge(msg("""{"type":"conv","conv":{"id":"c9"},"created_by":"s22-natif"}"""))
    assertEquals("voice", io.sent.last().getString("type"))
    assertEquals("c9", io.sent.last().getString("conv"))
    assertEquals("c9", sink.conv)
  }

  @Test fun bridge_ancien_origin_refuse_talk_devient_watch() {
    val io = FakeIo(); val r = Relay(io); val sink = Sink()
    r.submitNow(Relay.Req("t5", voice("t5"), sink, speak = true))
    r.onError(msg("""{"type":"error","code":"bad_field","message":"voice.origin : watch attendu","id":"t5"}"""))
    assertEquals("watch", io.sent.last().getString("origin"))
    assertFalse(sink.states.contains("error"))
    // assist / share : l'origine est retiree (reponse normale plutot qu'un refus)
    val chat = JSONObject().put("type", "chat").put("id", "a1").put("text", "Q").put("origin", "assist")
    r.submitNow(Relay.Req("a1", chat, Sink()))
    r.onError(msg("""{"type":"error","code":"bad_field","message":"chat.origin : watch attendu","id":"a1"}"""))
    assertFalse(io.sent.last().has("origin"))
  }

  @Test fun annulation_barge_in() {
    val io = FakeIo(); val r = Relay(io); val sink = Sink()
    r.submitNow(Relay.Req("t6", voice("t6"), sink, speak = true))
    r.onBridge(msg("""{"type":"tts_audio","id":"t6","seq":0,"last":false,"audio":"${b64("A")}"}"""))
    r.cancel("t6")
    assertTrue(io.types().containsAll(listOf("tts_cancel", "cancel")))
    r.onBridge(msg("""{"type":"tts_audio","id":"t6","seq":1,"last":true,"audio":"${b64("B")}"}"""))
    r.onBridge(msg("""{"type":"done","conv":"c1","id":"t6","text":"x","error":null}"""))
    assertEquals(listOf(0 to "A"), sink.audio)
    assertFalse(sink.states.contains("done"))
  }

  @Test fun annuler_pendant_le_repli_coupe_la_voix_du_repli() {
    val io = FakeIo(); val r = Relay(io); val sink = Sink()
    r.submitNow(Relay.Req("t9", voice("t9"), sink, speak = true))
    r.onBridge(msg("""{"type":"done","conv":"c1","id":"t9","text":"Salut.","error":null}"""))
    io.advance(3100)
    r.cancel("t9")
    assertEquals("tts_cancel", io.sent.last().getString("type"))
    assertEquals("t9.tts", io.sent.last().getString("id"))
    r.onBridge(msg("""{"type":"tts_audio","id":"t9.tts","seq":0,"last":true,"audio":"${b64("V")}"}"""))
    assertTrue(sink.audio.isEmpty())
  }

  @Test fun hors_ligne_attend_12s_puis_offline() {
    val io = FakeIo(); io.isOnline = false
    val r = Relay(io); val sink = Sink()
    r.submitNow(Relay.Req("t7", voice("t7"), sink, speak = true))
    assertEquals(listOf("offline"), sink.states)
    assertTrue(io.clock >= Relay.ONLINE_WAIT_MS)
  }

  @Test fun montre_sans_speak_tts_apres_done() {
    val io = FakeIo(); val r = Relay(io)
    val sink = object : RelaySink {
      val audio = mutableListOf<Int>()
      override fun ttsAfterDone() = true
      override fun audio(seq: Int, mp3: ByteArray, last: Boolean, text: String?) { audio += seq }
    }
    val chat = JSONObject().put("type", "chat").put("id", "w1").put("text", "heure").put("origin", "watch")
    r.submitNow(Relay.Req("w1", chat, sink))
    assertFalse(io.sent[0].has("speak"))
    r.onBridge(msg("""{"type":"done","conv":"c1","id":"w1","text":"14 h","error":null}"""))
    assertEquals("tts", io.sent.last().getString("type"))
    r.onBridge(msg("""{"type":"tts_audio","id":"w1","seq":0,"last":true,"audio":"${b64("M")}"}"""))
    assertEquals(listOf(0), sink.audio)
  }

  @Test fun partage_dans_une_conversation_neuve() {
    val io = FakeIo(); val r = Relay(io); val sink = Sink()
    val chat = JSONObject().put("type", "chat").put("id", "s1").put("text", "Résume").put("origin", "share")
    r.submitNow(Relay.Req("s1", chat, sink, newConv = true))
    assertEquals(listOf("conv_new"), io.types())
    assertTrue(sink.states.isEmpty())
    // conversation creee par un AUTRE poste : pas la notre
    r.onBridge(msg("""{"type":"conv","conv":{"id":"x1"},"created_by":"pc-bureau"}"""))
    assertEquals(1, io.sent.size)
    r.onBridge(msg("""{"type":"conv","conv":{"id":"c7"},"created_by":"s22-natif"}"""))
    assertEquals("c7", io.sent.last().getString("conv"))
    assertEquals("share", io.sent.last().getString("origin"))
    assertEquals(listOf("sent"), sink.states)
    assertEquals("c7", sink.conv)
  }

  @Test fun busy_reconnait_son_onglet_a_req_pas_celui_de_la_montre() {
    // meme poste pour la montre (via le S22) et le relais : seul `req` les distingue (bridge du 28/09)
    val io = FakeIo(); val r = Relay(io); val sink = Sink()
    r.submitNow(Relay.Req("t6", voice("t6"), sink))
    r.onError(msg("""{"type":"error","code":"busy","message":"Je réponds déjà","conv":"c1","id":"t6"}"""))
    assertEquals("relay:t6", io.sent.last().getString("req"))
    r.onBridge(msg("""{"type":"conv","conv":{"id":"w1"},"created_by":"s22-natif","req":"w-abc"}"""))
    assertEquals("conv_new", io.sent.last().getString("type"))
    r.onBridge(msg("""{"type":"conv","conv":{"id":"c8"},"created_by":"s22-natif","req":"relay:t6"}"""))
    assertEquals("c8", io.sent.last().getString("conv"))
    assertEquals("c8", sink.conv)
  }

  @Test fun done_vide_est_une_erreur() {
    val io = FakeIo(); val r = Relay(io); val sink = Sink()
    r.submitNow(Relay.Req("t8", voice("t8"), sink, speak = true))
    r.onBridge(msg("""{"type":"done","conv":"c1","id":"t8","text":"","error":"vocal vide"}"""))
    assertEquals("error", sink.states.last())
    io.advance(5000)
    assertFalse(io.types().contains("tts"))
  }
}
