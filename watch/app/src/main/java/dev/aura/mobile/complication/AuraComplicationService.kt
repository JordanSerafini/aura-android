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
import dev.aura.mobile.protocol.LaunchCounters
import dev.aura.mobile.protocol.StatusLogic
import dev.aura.mobile.ui.MainActivity
import dev.aura.mobile.wear.ConfirmCenter
import kotlinx.coroutines.withTimeoutOrNull

/** Complication « Aura » (SHORT_TEXT ou MONOCHROMATIC_IMAGE) : état du téléphone, ouvre l'app. */
class AuraComplicationService : SuspendingComplicationDataSourceService() {
  override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
    val app = AuraApp.get(this)
    val status = AuraBus.status.value ?: runCatching { app.store.status() }.getOrNull()
    val reachable = withTimeoutOrNull(REACH_TIMEOUT_MS) { app.phone.isPhoneReachable() }
    val model = StatusLogic.complication(status, reachable, System.currentTimeMillis() / 1000)
    // une demande connue de la montre s'ouvre au toucher, même si le compte du téléphone n'est pas (encore) arrivé
    return build(request.complicationType, model.copy(openConfirm = model.openConfirm || ConfirmCenter.oldestLive() != null))
  }

  override fun getPreviewData(type: ComplicationType): ComplicationData? =
    build(type, StatusLogic.ComplicationModel("OK", "Aura", "Aura connectée, ouvrir"), tap = false)

  private companion object {
    const val REACH_TIMEOUT_MS = 2_500L
    const val REQUEST_OPEN = 0
    const val REQUEST_CONFIRM = 1
  }

  /**
   * Texte = nombre de confirmations en attente, « Pause », « Hors » ou « OK » (PROTOCOL.md §10) ; toucher ouvre l'app,
   * et depuis 0.2.0 la demande en attente quand il y en a une (`EXTRA_CONFIRM`, la plus ancienne non échue).
   */
  private fun build(type: ComplicationType, model: StatusLogic.ComplicationModel, tap: Boolean = true): ComplicationData? {
    val image = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_aura)).build()
    val description = PlainComplicationText.Builder(model.description).build()
    val tapAction = if (tap) {
      val open = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(LaunchCounters.EXTRA_SOURCE, LaunchCounters.COMPLICATION)
      if (model.openConfirm) open.putExtra(MainActivity.EXTRA_CONFIRM, true)
      // deux codes de requête : un PendingIntent par variante (avec ou sans demande à ouvrir), jamais l'un pour l'autre
      PendingIntent.getActivity(
        this,
        if (model.openConfirm) REQUEST_CONFIRM else REQUEST_OPEN,
        open,
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
