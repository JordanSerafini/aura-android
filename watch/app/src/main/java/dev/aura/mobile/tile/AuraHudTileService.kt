package dev.aura.mobile.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dev.aura.mobile.AuraApp
import dev.aura.mobile.data.AuraBus
import dev.aura.mobile.protocol.HudWatch
import dev.aura.mobile.protocol.LaunchCounters
import dev.aura.mobile.ui.AuraPalette
import dev.aura.mobile.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Tuile « Aura HUD » (0.3.0, 22/10, PROTOCOL.md §35) : le HUD du PC au poignet, relu dans l'etat publie par le
 * telephone (`/aura/status`, champ `hud`) : alertes (couleur de gravite), prochain rendez-vous, nombre « à traiter ».
 * Lecture seule : un toucher ouvre l'app montre (les actions restent sur le telephone et le PC).
 */
class AuraHudTileService : TileService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
    CallbackToFutureAdapter.getFuture { completer ->
      scope.launch {
        val app = AuraApp.get(this@AuraHudTileService)
        val status = AuraBus.status.value ?: runCatching { app.store.status() }.getOrNull()
        completer.set(buildTile(HudWatch.model(status, System.currentTimeMillis() / 1000), "AURA HUD"))
      }
      "aura-hud-tile"
    }

  @Deprecated("Requis par l'API Tiles pour fournir les images de la tuile")
  override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
    CallbackToFutureAdapter.getFuture { completer ->
      completer.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())
      "aura-hud-tile-resources"
    }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun buildTile(m: HudWatch.Model, header: String): TileBuilders.Tile {
    val open = ActionBuilders.LaunchAction.Builder()
      .setAndroidActivity(
        ActionBuilders.AndroidActivity.Builder()
          .setPackageName(packageName)
          .setClassName(MainActivity::class.java.name)
          .addKeyToExtraMapping(LaunchCounters.EXTRA_SOURCE, ActionBuilders.AndroidStringExtra.Builder().setValue(LaunchCounters.TILE).build())
          .build(),
      )
      .build()
    val tone = when (m.level) {
      HudWatch.Level.GRAVE -> BAD
      HudWatch.Level.ALERT -> WARN
      HudWatch.Level.OK -> GOOD
      HudWatch.Level.UNKNOWN -> MUTED
    }
    val column = LayoutElementBuilders.Column.Builder()
      .setWidth(expand())
      .setHeight(wrap())
      .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
      .addContent(text(header, 11f, ACCENT, bold = true, maxLines = 1))
      .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
      .addContent(text(m.shortText, 34f, tone, bold = true, maxLines = 1))
      .addContent(text(m.headline, 13f, tone, bold = true, maxLines = 1))
      .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
      .addContent(
        LayoutElementBuilders.Box.Builder().setWidth(dp(160f))
          .addContent(text(m.rdv, 13f, TEXT, bold = false, maxLines = 2)).build(),
      )
      .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
      .addContent(text(m.todo, 12f, MUTED, bold = false, maxLines = 1))
      .build()
    val root = LayoutElementBuilders.Box.Builder()
      .setWidth(expand())
      .setHeight(expand())
      .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
      .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
      .setModifiers(
        ModifiersBuilders.Modifiers.Builder()
          .setClickable(ModifiersBuilders.Clickable.Builder().setId("hud_open").setOnClick(open).build())
          .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(m.description).build())
          .build(),
      )
      .addContent(column)
      .build()
    return TileBuilders.Tile.Builder()
      .setResourcesVersion(RESOURCES_VERSION)
      // l'etat change par le DataItem du telephone (StatusSurfaces.refresh) ; 5 min = le « PC injoignable » arrive a l'heure
      .setFreshnessIntervalMillis(5 * 60_000L)
      .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
      .build()
  }

  private fun text(value: String, size: Float, color: Int, bold: Boolean, maxLines: Int) =
    LayoutElementBuilders.Text.Builder()
      .setText(value)
      .setMaxLines(maxLines)
      .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
      .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
      .setFontStyle(
        LayoutElementBuilders.FontStyle.Builder()
          .setSize(sp(size))
          .setColor(argb(color))
          .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
          .build(),
      )
      .build()

  private companion object {
    const val RESOURCES_VERSION = "2"
    const val ACCENT = AuraPalette.ACCENT.toInt()
    const val TEXT = AuraPalette.TEXT.toInt()
    const val MUTED = AuraPalette.MUTED.toInt()
    const val WARN = AuraPalette.ALERT.toInt()
    const val GOOD = AuraPalette.OK.toInt()
    const val BAD = AuraPalette.DANGER.toInt()
    const val CHIP_BG = 0xFF2A2150.toInt()
  }
}
