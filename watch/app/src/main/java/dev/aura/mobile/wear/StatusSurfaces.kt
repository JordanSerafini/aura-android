package dev.aura.mobile.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import dev.aura.mobile.complication.AuraComplicationService
import dev.aura.mobile.complication.AuraHudComplicationService
import dev.aura.mobile.tile.AuraHudTileService
import dev.aura.mobile.tile.AuraTileService

/** Tuiles et complications relisent l'etat du telephone (pause, confirmations en attente, lien) apres un changement. */
object StatusSurfaces {
  fun refresh(context: Context) {
    runCatching { TileService.getUpdater(context).requestUpdate(AuraTileService::class.java) }
    runCatching {
      ComplicationDataSourceUpdateRequester
        .create(context, ComponentName(context, AuraComplicationService::class.java))
        .requestUpdateAll()
    }
    // 0.3.0 : tuile et complication « Aura HUD » (champ `hud` du meme etat)
    runCatching { TileService.getUpdater(context).requestUpdate(AuraHudTileService::class.java) }
    runCatching {
      ComplicationDataSourceUpdateRequester
        .create(context, ComponentName(context, AuraHudComplicationService::class.java))
        .requestUpdateAll()
    }
  }
}
