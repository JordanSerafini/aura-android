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
import dev.aura.mobile.R
import dev.aura.mobile.data.AuraBus
import dev.aura.mobile.protocol.AuraStatus
import dev.aura.mobile.protocol.Paths
import dev.aura.mobile.protocol.PauseSet
import dev.aura.mobile.protocol.Protocol
import dev.aura.mobile.protocol.StatusLogic
import dev.aura.mobile.protocol.TileClicks
import dev.aura.mobile.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Tuile « Aura » : gros bouton « Parler » (ouvre l'app en enregistrement immédiat), bandeau d'état (connexion ou pause),
 * bouton « Pause » / « Reprendre » (bascule `apps` 1 h puis `off`, par le téléphone), nombre de confirmations en
 * attente et dernière réponse. L'état vient du téléphone (`/aura/status`, PROTOCOL.md §10).
 */
class AuraTileService : TileService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
    CallbackToFutureAdapter.getFuture { completer ->
      scope.launch {
        val app = AuraApp.get(this@AuraTileService)
        // lastClickableId est remanent (rejoue a chaque requestUpdate) : seul un clic pas encore traite bascule la pause
        val lastHandled = runCatching { app.store.lastTileClick() }.getOrDefault(0L)
        TileClicks.pausePending(requestParams.currentState.lastClickableId, lastHandled)?.let { nonce ->
          runCatching { app.store.saveTileClick(nonce) }  // note AVANT d'agir : un echec ne doit pas reboucler
          togglePause(app)
        }
        val last = runCatching { app.store.lastReply()?.second }.getOrNull()
        val status = AuraBus.status.value ?: runCatching { app.store.status() }.getOrNull()
        val reachable = withTimeoutOrNull(REACH_TIMEOUT_MS) { app.phone.isPhoneReachable() }
        completer.set(buildTile(last, status, reachable, nextNonce()))
      }
      "aura-tile"
    }

  /** Appui sur « Pause » : demande au téléphone (qui l'applique et prévient le bridge) ; la tuile suit seulement si c'est parti. */
  private suspend fun togglePause(app: AuraApp) {
    val status = AuraBus.status.value ?: runCatching { app.store.status() }.getOrNull()
    val set: PauseSet = StatusLogic.togglePause(status, System.currentTimeMillis() / 1000)
    if (!app.phone.send(Paths.PAUSE_SET, Protocol.encode(set))) return
    val next = StatusLogic.applied(status, set)
    AuraBus.status.value = next
    runCatching { app.store.saveStatus(Protocol.json.encodeToString(AuraStatus.serializer(), next)) }
  }

  @Deprecated("Requis par l'API Tiles pour fournir les images de la tuile")
  override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
    CallbackToFutureAdapter.getFuture { completer ->
      completer.set(
        ResourceBuilders.Resources.Builder()
          .setVersion(RESOURCES_VERSION)
          .addIdToImageMapping(
            ID_MIC,
            ResourceBuilders.ImageResource.Builder()
              .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(R.drawable.ic_mic).build())
              .build(),
          )
          .build(),
      )
      "aura-tile-resources"
    }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  // Image.Builder() sans ProtoLayoutScope est dépréciée en ProtoLayout 1.4, mais reste la forme lue par
  // tous les moteurs de rendu de tuiles : on la garde tant que la montre n'est pas sur un renderer récent.
  @Suppress("DEPRECATION")
  private fun buildTile(lastReply: String?, status: AuraStatus?, phoneReachable: Boolean?, clickNonce: Long): TileBuilders.Tile {
    val nowS = System.currentTimeMillis() / 1000
    val model = StatusLogic.tile(status, phoneReachable, nowS)

    val launch = ActionBuilders.LaunchAction.Builder()
      .setAndroidActivity(
        ActionBuilders.AndroidActivity.Builder()
          .setPackageName(packageName)
          .setClassName(MainActivity::class.java.name)
          .addKeyToExtraMapping(MainActivity.EXTRA_RECORD, ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build())
          .build(),
      )
      .build()

    // pastille « N à valider » : ouvre l'app, qui rouvre la demande en attente
    val openConfirm = ActionBuilders.LaunchAction.Builder()
      .setAndroidActivity(
        ActionBuilders.AndroidActivity.Builder()
          .setPackageName(packageName)
          .setClassName(MainActivity::class.java.name)
          .addKeyToExtraMapping(MainActivity.EXTRA_CONFIRM, ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build())
          .build(),
      )
      .build()

    val talkButton = LayoutElementBuilders.Box.Builder()
      .setWidth(dp(BUTTON_DP))
      .setHeight(dp(BUTTON_DP))
      .setModifiers(
        ModifiersBuilders.Modifiers.Builder()
          .setClickable(ModifiersBuilders.Clickable.Builder().setId("talk").setOnClick(launch).build())
          .setBackground(
            ModifiersBuilders.Background.Builder()
              .setColor(argb(ACCENT))
              .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(BUTTON_DP / 2)).build())
              .build(),
          )
          .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription("Parler à Aura").build())
          .build(),
      )
      .addContent(
        LayoutElementBuilders.Column.Builder()
          .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
          .addContent(
            LayoutElementBuilders.Image.Builder()
              .setResourceId(ID_MIC)
              .setWidth(dp(30f))
              .setHeight(dp(30f))
              .setColorFilter(LayoutElementBuilders.ColorFilter.Builder().setTint(argb(ON_ACCENT)).build())
              .build(),
          )
          .addContent(text("Parler", 13f, ON_ACCENT, bold = true, maxLines = 1))
          .build(),
      )
      .build()

    val chips = LayoutElementBuilders.Row.Builder()
      .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
      .addContent(
        chip(
          label = model.pauseButton,
          id = TileClicks.pauseId(clickNonce),
          action = ActionBuilders.LoadAction.Builder().build(),
          background = if (model.paused) WARN else CHIP,
          foreground = if (model.paused) ON_WARN else TEXT,
          description = if (model.paused) "Reprendre Aura" else "Mettre Aura en pause une heure",
        ),
      )
    if (model.confirms > 0) {
      chips.addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
      chips.addContent(
        chip(
          label = "${model.confirms} à valider",
          id = "confirm",
          action = openConfirm,
          background = GOOD,
          foreground = ON_WARN,
          description = if (model.confirms == 1) "1 confirmation en attente" else "${model.confirms} confirmations en attente",
        ),
      )
    }

    val column = LayoutElementBuilders.Column.Builder()
      .setWidth(expand())
      .setHeight(wrap())
      .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
      .addContent(text(model.headline, 12f, toneColor(model.tone), bold = true, maxLines = 1))
      .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
      .addContent(talkButton)
      .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
      .addContent(chips.build())
      .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(2f)).build())
      .addContent(
        LayoutElementBuilders.Box.Builder()
          .setWidth(dp(140f))
          .addContent(text(truncate(lastReply, 48) ?: "Aucune réponse", 11f, MUTED, bold = false, maxLines = 1))
          .build(),
      )
      .build()

    val root = LayoutElementBuilders.Box.Builder()
      .setWidth(expand())
      .setHeight(expand())
      .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
      .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
      .addContent(column)
      .build()

    return TileBuilders.Tile.Builder()
      .setResourcesVersion(RESOURCES_VERSION)
      .setFreshnessIntervalMillis(freshnessMs(status, nowS))
      .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
      .build()
  }

  /** Bouton de 48 dp de haut (cible tactile) : pastille arrondie avec un libellé court. */
  private fun chip(
    label: String,
    id: String,
    action: ActionBuilders.Action,
    background: Int,
    foreground: Int,
    description: String,
  ) = LayoutElementBuilders.Box.Builder()
    .setHeight(dp(CHIP_DP))
    .setModifiers(
      ModifiersBuilders.Modifiers.Builder()
        .setClickable(ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(action).build())
        .setBackground(
          ModifiersBuilders.Background.Builder()
            .setColor(argb(background))
            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(CHIP_DP / 2)).build())
            .build(),
        )
        .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(12f)).setEnd(dp(12f)).build())
        .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(description).build())
        .build(),
    )
    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
    .addContent(text(label, 12f, foreground, bold = true, maxLines = 1))
    .build()

  private fun toneColor(tone: StatusLogic.Tone) = when (tone) {
    StatusLogic.Tone.OK -> GOOD
    StatusLogic.Tone.WARN -> WARN
    StatusLogic.Tone.BAD -> BAD
    StatusLogic.Tone.DIM -> MUTED
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

  companion object {
    private const val RESOURCES_VERSION = "1"
    private const val ID_MIC = "mic"
    private const val BUTTON_DP = 72f
    private const val CHIP_DP = 48f
    private val nonce = AtomicLong(0L)

    /** Id de clic unique par rendu (TileClicks) : l'heure, strictement croissante. */
    private fun nextNonce(): Long = nonce.updateAndGet { maxOf(System.currentTimeMillis(), it + 1) }
    private const val REACH_TIMEOUT_MS = 2_500L
    private const val FRESH_MAX_MS = 5 * 60_000L
    private const val ACCENT = 0xFF7C4DFF.toInt()
    private const val ON_ACCENT = 0xFFFFFFFF.toInt()
    private const val TEXT = 0xFFE6E1E5.toInt()
    private const val MUTED = 0xFFB9A8FF.toInt()
    private const val CHIP = 0xFF2B2A33.toInt()
    private const val WARN = 0xFFFFB74D.toInt()
    private const val ON_WARN = 0xFF1F1300.toInt()
    private const val GOOD = 0xFF81C995.toInt()
    private const val BAD = 0xFFF28B82.toInt()

    /**
     * Prochain rafraîchissement : 5 min, ou l'échéance de la pause si elle tombe avant (la tuile redevient « normale »
     * à l'heure), ou le moment où l'état du téléphone devient périmé (« Connecté » ne doit pas survivre à son âge).
     */
    fun freshnessMs(status: AuraStatus?, nowS: Long): Long {
      var next = FRESH_MAX_MS
      val until = status?.pause?.until ?: 0L
      if (StatusLogic.pauseMode(status, nowS) != "off" && until > 0L) next = minOf(next, (until - nowS) * 1000 + 1_000)
      StatusLogic.staleInS(status, nowS)?.let { next = minOf(next, it * 1000) }
      return next.coerceIn(10_000L, FRESH_MAX_MS)
    }

    fun truncate(text: String?, max: Int = 80): String? {
      val t = text?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() } ?: return null
      return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }
  }
}
