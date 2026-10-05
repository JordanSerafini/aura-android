package dev.aura.mobile.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesTest {
  private fun parse(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject

  @Test
  fun chatEncodesIdAndText() {
    val json = parse(Protocol.encode(ChatMessage("a1b2c3d4", "Quoi de neuf ?")))
    assertEquals(setOf("id", "text"), json.keys)
    assertEquals("Quoi de neuf ?", json["text"]!!.jsonPrimitive.content)
  }

  @Test
  fun stateDecodesWithAndWithoutText() {
    val s = Protocol.decode<StateMessage>("""{"id":"x","state":"thinking"}""".toByteArray())!!
    assertEquals(States.THINKING, s.state)
    assertNull(s.text)
    val e = Protocol.decode<StateMessage>("""{"id":"x","state":"error","text":"Bridge injoignable","extra":1}""".toByteArray())!!
    assertEquals("Bridge injoignable", e.text)
  }

  @Test
  fun replyDecodesUtf8() {
    val r = Protocol.decode<ReplyMessage>("""{"id":"x","text":"Réunion à 14 h, café ☕","final":true}""".toByteArray(Charsets.UTF_8))!!
    assertTrue(r.final)
    assertEquals("Réunion à 14 h, café ☕", r.text)
  }

  @Test
  fun confirmUsesSnakeCase() {
    val req = Protocol.decode<ConfirmRequest>("""{"action_id":"act-1","summary":"Envoyer un SMS à Maman","timeout_s":45}""".toByteArray())!!
    assertEquals("act-1", req.actionId)
    assertEquals(45, req.timeoutS)
    val resp = parse(Protocol.encode(ConfirmResponse("act-1", ok = false)))
    assertEquals(setOf("action_id", "ok"), resp.keys)
    assertEquals("false", resp["ok"]!!.jsonPrimitive.content)
  }

  @Test
  fun cmdDecodesParamsAndDefaults() {
    val cmd = Protocol.decode<CmdMessage>("""{"req_id":"r1","cmd":"vibrate","params":{"pattern":"double"}}""".toByteArray())!!
    assertEquals("r1", cmd.reqId)
    assertEquals("double", cmd.params["pattern"]!!.jsonPrimitive.content)
    val bare = Protocol.decode<CmdMessage>("""{"req_id":"r2","cmd":"battery"}""".toByteArray())!!
    assertTrue(bare.params.isEmpty())
  }

  @Test
  fun cmdResultOkOmitsError() {
    val json = parse(Protocol.encode(Results.ok("r1", "bpm" to 72)))
    assertEquals(setOf("req_id", "ok", "result"), json.keys)
    assertEquals(72, json["result"]!!.jsonObject["bpm"]!!.jsonPrimitive.int)
  }

  @Test
  fun cmdResultErrorOmitsResult() {
    val json = parse(Protocol.encode(Results.permission("r9", "heart_rate")))
    assertEquals(setOf("req_id", "ok", "error"), json.keys)
    assertEquals("permission:heart_rate", json["error"]!!.jsonPrimitive.content)
    assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
  }

  @Test
  fun batteryResultShape() {
    val json = parse(Protocol.encode(Results.ok("b", "percent" to 81, "charging" to true)))
    val result = json["result"]!!.jsonObject
    assertEquals(81, result["percent"]!!.jsonPrimitive.int)
    assertEquals("true", result["charging"]!!.jsonPrimitive.content)
  }

  @Test
  fun garbageDecodesToNull() {
    assertNull(Protocol.decode<StateMessage>("pas du json".toByteArray()))
    assertNull(Protocol.decode<StateMessage>(ByteArray(0)))
    assertNull(Protocol.decode<StateMessage>(null))
  }

  @Test
  fun settingsFromJsonBytes() {
    val s = SettingsParser.fromJsonBytes(
      """{"voice_mode":"text","work_hours":{"days":[1,2,3,4,5],"start":"09:00","end":"18:00"}}""".toByteArray(),
    )!!
    assertEquals(VoiceMode.TEXT, s.voiceMode)
    assertEquals("09:00", s.workHours.start)
    assertNull(SettingsParser.fromJsonBytes(byteArrayOf(0x00, 0x01, 0x02)))
  }

  @Test
  fun settingsDefaultsWhenPartial() {
    val s = SettingsParser.fromJson("""{"voice_mode":"voice"}""")!!
    assertEquals(WorkHours(), s.workHours)
    val parts = SettingsParser.fromParts("auto", null, intArrayOf(1, 2), "08:00", null)
    assertEquals(listOf(1, 2), parts.workHours.days)
    assertEquals("17:30", parts.workHours.end)
  }

  @Test
  fun settingsRoundTrip() {
    val s = WatchSettings(VoiceMode.VOICE, WorkHours(listOf(1, 3), "07:15", "12:00"))
    assertEquals(s, SettingsParser.fromJson(SettingsParser.toJson(s)))
  }

  @Test
  fun audioIdFromPath() {
    assertEquals("ab12", Paths.audioId("/aura/audio/ab12"))
    assertNull(Paths.audioId("/aura/audio/"))
    assertNull(Paths.audioId("/aura/voice/ab12"))
    assertEquals("/aura/voice/ab12", Paths.voice("ab12"))
  }

  @Test
  fun shortIdIsEightHexChars() {
    val id = Protocol.shortId()
    assertTrue(id.matches(Regex("[0-9a-f]{8}")))
  }

  @Test
  fun convListDecodesPhonePayload() {
    // format exact de WatchConvs.payload() cote S22 : `active` JSONObject.NULL, `updated` en secondes flottantes
    val raw = """{"convs":[{"id":"c1","title":"ONGLET-A","busy":true,"updated":1790000000.5},
      {"id":"c2","title":"ONGLET-B","busy":false,"updated":1789990000}],"active":null}"""
    val list = Protocol.decode<ConvList>(raw.toByteArray())!!
    assertEquals(listOf("ONGLET-A", "ONGLET-B"), list.convs.map { it.title })
    assertTrue(list.convs[0].busy)
    assertNull(list.active)
    assertEquals("c2", Protocol.decode<ConvList>("""{"convs":[],"active":"c2"}""".toByteArray())!!.active)
  }

  @Test
  fun convSelectAutoSendsNoConv() {
    // « La plus recente » : pas de cle `conv`, le S22 lit null et efface le choix
    assertEquals(emptySet<String>(), parse(Protocol.encode(ConvSelect(null))).keys)
    assertEquals("c1", parse(Protocol.encode(ConvSelect("c1")))["conv"]!!.jsonPrimitive.content)
  }

  @Test
  fun convListDecodesCreatedAndError() {
    val ok = Protocol.decode<ConvList>("""{"convs":[{"id":"c3","title":"","busy":false,"updated":0}],"active":"c3","created":"ab12cd34"}""".toByteArray())!!
    assertEquals("ab12cd34", ok.created)
    assertNull(ok.error)
    assertFalse(ok.pcOffline)
    val off = Protocol.decode<ConvList>("""{"convs":[],"active":null,"error":"offline"}""".toByteArray())!!
    assertTrue(off.pcOffline)
    assertNull(off.created)
    // ancien format (sans created/error) : toujours lisible
    assertNull(Protocol.decode<ConvList>("""{"convs":[],"active":null}""".toByteArray())!!.error)
  }

  @Test
  fun convNewCarriesReq() {
    assertEquals("r1", parse(Protocol.encode(ConvNew("r1")))["req"]!!.jsonPrimitive.content)
  }

  @Test
  fun newConvOutcomeFromList() {
    val pending = NewConv("r1")
    assertEquals(NewConvOutcome.CREATED, NewConvLogic.onList(pending, ConvList(active = "c9", created = "r1")))
    assertEquals(NewConvOutcome.PC_OFFLINE, NewConvLogic.onList(pending, ConvList(error = ConvErrors.OFFLINE)))
    // liste spontanée (changement d'onglet) ou création d'une autre requête : sans effet
    assertEquals(NewConvOutcome.NONE, NewConvLogic.onList(pending, ConvList(active = "c2")))
    assertEquals(NewConvOutcome.NONE, NewConvLogic.onList(pending, ConvList(created = "autre")))
    assertEquals(NewConvOutcome.NONE, NewConvLogic.onList(null, ConvList(created = "r1")))
    assertEquals(NewConvOutcome.NONE, NewConvLogic.onList(pending.copy(status = NewConvStatus.PC_OFFLINE), ConvList(created = "r1")))
  }
}
