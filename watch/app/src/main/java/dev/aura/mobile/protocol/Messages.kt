package dev.aura.mobile.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** Messages JSON UTF-8 échangés avec le téléphone (PROTOCOL.md section 3). */

@Serializable
data class ChatMessage(val id: String, val text: String)

@Serializable
data class StateMessage(val id: String = "", val state: String, val text: String? = null)

@Serializable
data class ReplyMessage(val id: String = "", val text: String = "", val final: Boolean = false)

@Serializable
data class ConfirmRequest(
  @SerialName("action_id") val actionId: String,
  val summary: String = "",
  @SerialName("timeout_s") val timeoutS: Int = 30,
)

@Serializable
data class ConfirmResponse(@SerialName("action_id") val actionId: String, val ok: Boolean)

@Serializable
data class ConfirmCancel(@SerialName("action_id") val actionId: String)

@Serializable
data class CmdMessage(
  @SerialName("req_id") val reqId: String,
  val cmd: String,
  val params: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class CmdResult(
  @SerialName("req_id") val reqId: String,
  val ok: Boolean,
  val result: JsonElement? = null,
  val error: String? = null,
)

/** Un onglet ouvert du bridge (un fil de discussion), tel que le S22 le relaie. */
@Serializable
data class ConvItem(val id: String, val title: String = "", val busy: Boolean = false, val updated: Double = 0.0)

/**
 * `/aura/convs` : `active` = conversation choisie sur la montre, null = la plus récente (comportement d'avant).
 * `created` = le `req` d'un `/aura/conv_new` dont l'onglet vient d'être créé et sélectionné ;
 * `error` = "offline" quand le PC (bridge) est injoignable : la liste est alors celle en cache du S22.
 */
@Serializable
data class ConvList(
  val convs: List<ConvItem> = emptyList(),
  val active: String? = null,
  val created: String? = null,
  val error: String? = null,
) {
  val pcOffline: Boolean get() = error == ConvErrors.OFFLINE
}

object ConvErrors {
  const val OFFLINE = "offline"
}

/** `/aura/conv_new` : `req` généré par la montre, renvoyé par le S22 dans `ConvList.created`. */
@Serializable
data class ConvNew(val req: String)

@Serializable
data class ConvSelect(val conv: String? = null)

@Serializable
data class WorkHours(
  val days: List<Int> = listOf(1, 2, 3, 4, 5),
  val start: String = "08:30",
  val end: String = "17:30",
)

@Serializable
data class WatchSettings(
  @SerialName("voice_mode") val voiceMode: String = VoiceMode.AUTO,
  @SerialName("work_hours") val workHours: WorkHours = WorkHours(),
)

object VoiceMode {
  const val AUTO = "auto"
  const val VOICE = "voice"
  const val TEXT = "text"
}

/** États `/aura/state` connus. */
object States {
  const val SENT = "sent"
  const val TRANSCRIBING = "transcribing"
  const val THINKING = "thinking"
  const val DONE = "done"
  const val ERROR = "error"
  const val OFFLINE = "offline"
}

object Protocol {
  /** Tolérant en lecture (champs inconnus ignorés), sans `null` explicites en écriture. */
  val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
  }

  inline fun <reified T> encode(value: T): ByteArray = json.encodeToString(value).toByteArray(Charsets.UTF_8)

  inline fun <reified T> decode(bytes: ByteArray?): T? {
    if (bytes == null || bytes.isEmpty()) return null
    return runCatching { json.decodeFromString<T>(bytes.toString(Charsets.UTF_8)) }.getOrNull()
  }

  val EMPTY: ByteArray = "{}".toByteArray(Charsets.UTF_8)

  /** Id court de requête (8 caractères hexadécimaux). */
  fun shortId(): String = UUID.randomUUID().toString().replace("-", "").take(8)
}
