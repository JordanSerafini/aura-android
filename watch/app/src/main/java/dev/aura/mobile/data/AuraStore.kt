package dev.aura.mobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.aura.mobile.protocol.AuraStatus
import dev.aura.mobile.protocol.SettingsParser
import dev.aura.mobile.protocol.StatusLogic
import dev.aura.mobile.protocol.WatchSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.auraDataStore: DataStore<Preferences> by preferencesDataStore(name = "aura")

/** Persistance locale (DataStore) : réglages reçus du téléphone, dernière réponse, cache des pas. */
class AuraStore(context: Context) {
  private val store = context.applicationContext.auraDataStore

  val settings: Flow<WatchSettings> = store.data.map { prefs ->
    prefs[SETTINGS]?.let { SettingsParser.fromJson(it) } ?: WatchSettings()
  }

  suspend fun settingsNow(): WatchSettings = settings.first()

  suspend fun saveSettings(value: WatchSettings) {
    store.edit { it[SETTINGS] = SettingsParser.toJson(value) }
  }

  suspend fun saveLastReply(id: String, text: String) {
    store.edit {
      it[LAST_REPLY_ID] = id
      it[LAST_REPLY_TEXT] = text
    }
  }

  suspend fun lastReply(): Pair<String, String>? {
    val prefs = store.data.first()
    val text = prefs[LAST_REPLY_TEXT] ?: return null
    return (prefs[LAST_REPLY_ID] ?: "") to text
  }

  /** Dernier `/aura/status` du telephone (tuile et complication lisibles meme sans lien avec le S22). */
  suspend fun saveStatus(json: String) {
    store.edit { it[STATUS] = json }
  }

  suspend fun status(): AuraStatus? = store.data.first()[STATUS]?.let { StatusLogic.parse(it.toByteArray(Charsets.UTF_8)) }

  /** Dernier clic « Pause » de la tuile deja traite (TileClicks) : le `lastClickableId` d'une requete de tuile est remanent. */
  suspend fun saveTileClick(nonce: Long) {
    store.edit { it[TILE_CLICK] = nonce }
  }

  suspend fun lastTileClick(): Long = store.data.first()[TILE_CLICK] ?: 0L

  suspend fun saveLastAudio(path: String) {
    store.edit { it[LAST_AUDIO] = path }
  }

  suspend fun lastAudio(): String? = store.data.first()[LAST_AUDIO]

  /** Dernière valeur STEPS_DAILY reçue de Health Services (jour ISO `yyyy-MM-dd`). */
  suspend fun savePassiveSteps(day: String, steps: Long) {
    store.edit {
      it[STEPS_DAY] = day
      it[STEPS_VALUE] = steps
    }
  }

  suspend fun passiveSteps(): Pair<String, Long>? {
    val prefs = store.data.first()
    val day = prefs[STEPS_DAY] ?: return null
    val value = prefs[STEPS_VALUE] ?: return null
    return day to value
  }

  suspend fun stepBaseline(): StepBaseline? {
    val prefs = store.data.first()
    val day = prefs[BASE_DAY] ?: return null
    val counter = prefs[BASE_COUNTER] ?: return null
    return StepBaseline(day, counter)
  }

  suspend fun saveStepBaseline(value: StepBaseline) {
    store.edit {
      it[BASE_DAY] = value.day
      it[BASE_COUNTER] = value.counter
    }
  }

  private companion object {
    val SETTINGS = stringPreferencesKey("settings_json")
    val STATUS = stringPreferencesKey("status_json")
    val LAST_REPLY_ID = stringPreferencesKey("last_reply_id")
    val LAST_REPLY_TEXT = stringPreferencesKey("last_reply_text")
    val TILE_CLICK = longPreferencesKey("tile_pause_click")
    val LAST_AUDIO = stringPreferencesKey("last_audio_path")
    val STEPS_DAY = stringPreferencesKey("steps_day")
    val STEPS_VALUE = longPreferencesKey("steps_value")
    val BASE_DAY = stringPreferencesKey("step_base_day")
    val BASE_COUNTER = longPreferencesKey("step_base_counter")
  }
}
