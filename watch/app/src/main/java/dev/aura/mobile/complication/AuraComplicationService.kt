package dev.aura.mobile.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dev.aura.mobile.AuraApp
import dev.aura.mobile.R
import dev.aura.mobile.data.AuraBus
import dev.aura.mobile.protocol.StatusLogic
import dev.aura.mobile.ui.MainActivity
import kotlinx.coroutines.withTimeoutOrNull

/** Complication « Aura » (SHORT_TEXT ou MONOCHROMATIC_IMAGE) : état du téléphone, ouvre l'app. */
class AuraComplicationService : SuspendingComplicationDataSourceService() {
  override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
    val app = AuraApp.get(this)
    val status = AuraBus.status.value ?: runCatching { app.store.status() }.getOrNull()
    val reachable = withTimeoutOrNull(REACH_TIMEOUT_MS) { app.phone.isPhoneReachable() }
    return build(request.complicationType, StatusLogic.complication(status, reachable, System.currentTimeMillis() / 1000))
  }

  override fun getPreviewData(type: ComplicationType): ComplicationData? =
    build(type, StatusLogic.ComplicationModel("OK", "Aura", "Aura connectée, ouvrir"), tap = false)

  private companion object {
    const val REACH_TIMEOUT_MS = 2_500L
  }

  /** Texte = nombre de confirmations en attente, « Pause », « Hors » ou « OK » (PROTOCOL.md §10) ; toucher ouvre l'app. */
  private fun build(type: ComplicationType, model: StatusLogic.ComplicationModel, tap: Boolean = true): ComplicationData? {
    val image = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_aura)).build()
    val description = PlainComplicationText.Builder(model.description).build()
    val tapAction = if (tap) {
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    } else {
      null
    }
    return when (type) {
      ComplicationType.SHORT_TEXT ->
        ShortTextComplicationData.Builder(PlainComplicationText.Builder(model.text).build(), description)
          .setTitle(PlainComplicationText.Builder(model.title).build())
          .setMonochromaticImage(image)
          .setTapAction(tapAction)
          .build()
      ComplicationType.MONOCHROMATIC_IMAGE ->
        MonochromaticImageComplicationData.Builder(image, description)
          .setTapAction(tapAction)
          .build()
      else -> null
    }
  }
}
