package dev.aura.mobile.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dev.aura.mobile.AuraApp
import dev.aura.mobile.R
import dev.aura.mobile.data.AuraBus
import dev.aura.mobile.protocol.HudWatch
import dev.aura.mobile.protocol.LaunchCounters
import dev.aura.mobile.ui.MainActivity

/**
 * Complication « Aura HUD » (0.3.0, PROTOCOL.md §35) : SHORT_TEXT = nombre d'alertes du PC (« ⚠2 » s'il y a du grave),
 * titre = heure du prochain rendez-vous. Toucher : l'app montre.
 */
class AuraHudComplicationService : SuspendingComplicationDataSourceService() {
  override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
    val app = AuraApp.get(this)
    val status = AuraBus.status.value ?: runCatching { app.store.status() }.getOrNull()
    return build(request.complicationType, HudWatch.model(status, System.currentTimeMillis() / 1000), tap = true)
  }

  override fun getPreviewData(type: ComplicationType): ComplicationData? =
    build(type, HudWatch.Model("2 alertes", HudWatch.Level.ALERT, "14:00 · Client", "1 à traiter", "2", "14:00", "Aura HUD"), tap = false)

  private fun build(type: ComplicationType, m: HudWatch.Model, tap: Boolean): ComplicationData? {
    if (type != ComplicationType.SHORT_TEXT) return null
    val open = if (tap) PendingIntent.getActivity(this, 2,
      Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(LaunchCounters.EXTRA_SOURCE, LaunchCounters.COMPLICATION),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) else null
    return ShortTextComplicationData.Builder(PlainComplicationText.Builder(m.shortText).build(),
      PlainComplicationText.Builder(m.description).build())
      .setTitle(PlainComplicationText.Builder(m.title).build())
      .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_aura)).build())
      .setTapAction(open)
      .build()
  }
}
