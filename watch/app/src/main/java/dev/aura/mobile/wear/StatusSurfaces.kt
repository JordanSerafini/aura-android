package dev.aura.mobile.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import dev.aura.mobile.complication.AuraComplicationService
import dev.aura.mobile.tile.AuraTileService

/** Tuile et complication relisent l'etat du telephone (pause, confirmations en attente, lien) apres un changement. */
object StatusSurfaces {
  fun refresh(context: Context) {
    runCatching { TileService.getUpdater(context).requestUpdate(AuraTileService::class.java) }
    runCatching {
      ComplicationDataSourceUpdateRequester
        .create(context, ComponentName(context, AuraComplicationService::class.java))
        .requestUpdateAll()
    }
  }
}
