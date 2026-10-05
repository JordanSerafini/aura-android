package dev.aura.mobile.protocol

/**
 * Le DataItem `/aura/settings` peut arriver sous deux formes :
 * - octets JSON UTF-8 bruts (`PutDataRequest.setData`), forme de référence ;
 * - DataMap (`PutDataMapRequest`) : clé `json` (chaîne JSON complète) ou clés `voice_mode` / `work_hours`
 *   (`work_hours` en chaîne JSON ou en DataMap `days` / `start` / `end`).
 * Cette partie pure décode le JSON ; l'extraction du DataMap vit dans le service (API Android).
 */
object SettingsParser {
  fun fromJsonBytes(bytes: ByteArray?): WatchSettings? {
    if (bytes == null || bytes.isEmpty()) return null
    val text = runCatching { bytes.toString(Charsets.UTF_8).trim() }.getOrNull() ?: return null
    if (!text.startsWith("{")) return null
    return fromJson(text)
  }

  fun fromJson(text: String): WatchSettings? =
    runCatching { Protocol.json.decodeFromString<WatchSettings>(text) }.getOrNull()

  fun fromParts(voiceMode: String?, workHoursJson: String?, days: IntArray?, start: String?, end: String?): WatchSettings {
    val defaults = WorkHours()
    val hours = workHoursJson?.let { runCatching { Protocol.json.decodeFromString<WorkHours>(it) }.getOrNull() }
      ?: WorkHours(
        days = days?.toList() ?: defaults.days,
        start = start ?: defaults.start,
        end = end ?: defaults.end,
      )
    return WatchSettings(voiceMode = voiceMode ?: VoiceMode.AUTO, workHours = hours)
  }

  fun toJson(settings: WatchSettings): String = Protocol.json.encodeToString(settings)
}
