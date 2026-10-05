package dev.aura.mobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.aura.mobile.R
import dev.aura.mobile.audio.VoiceRecorder
import dev.aura.mobile.audio.VolumeLogic
import dev.aura.mobile.data.AuraBus
import dev.aura.mobile.notify.Haptics
import dev.aura.mobile.protocol.ConvItem
import dev.aura.mobile.protocol.ConvList
import dev.aura.mobile.protocol.NewConvStatus
import dev.aura.mobile.protocol.States
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val QUICK_REPLIES = listOf("Quoi de neuf ?", "Mes RDV du jour", "Résumé de ma journée", "Rappelle-moi dans 10 min")

@Composable
fun AuraNavHost(
  isRecording: Boolean,
  recordStartedAt: Long,
  onMic: () -> Unit,
  onQuick: (String) -> Unit,
  onKeyboard: () -> Unit,
  onReplay: () -> Unit,
  onStopAudio: () -> Unit,
  onRequestConvs: suspend () -> Boolean,
  onSelectConv: suspend (String?) -> Boolean,
  onNewConv: () -> Unit,
  onLouder: () -> Boolean,
  onQuieter: () -> Boolean,
) {
  val nav = rememberSwipeDismissableNavController()
  // Relance depuis la tuile (ou une notification) : retour à l'écran principal, où qu'on soit.
  LaunchedEffect(nav) {
    AuraBus.goHome.collect { nav.popBackStack("main", inclusive = false) }
  }
  AppScaffold(timeText = { TimeText() }) {
    SwipeDismissableNavHost(navController = nav, startDestination = "main") {
      composable("main") {
        MainScreen(
          isRecording, recordStartedAt, onMic, onQuick, onKeyboard, onReplay, onStopAudio,
          onConvs = { nav.navigate("convs") }, onNewConv = onNewConv, onLouder = { onLouder() }, onQuieter = { onQuieter() },
          onVolume = { nav.navigate("volume") },
        ) { nav.navigate("settings") }
      }
      composable("settings") { SettingsScreen() }
      composable("volume") { VolumeScreen(onLouder, onQuieter) }
      composable("convs") {
        ConversationsScreen(
          onRequest = onRequestConvs,
          onNew = { onNewConv(); nav.popBackStack() },
          onPick = onSelectConv,
          onPicked = { nav.popBackStack() },
        )
      }
    }
  }
}

@Composable
fun MainScreen(
  isRecording: Boolean,
  recordStartedAt: Long,
  onMic: () -> Unit,
  onQuick: (String) -> Unit,
  onKeyboard: () -> Unit,
  onReplay: () -> Unit,
  onStopAudio: () -> Unit,
  onConvs: () -> Unit,
  onNewConv: () -> Unit,
  onLouder: () -> Unit,
  onQuieter: () -> Unit,
  onVolume: () -> Unit,
  onSettings: () -> Unit,
) {
  val conversation by AuraBus.conversation.collectAsState()
  val convs by AuraBus.convs.collectAsState()
  val newConv by AuraBus.newConv.collectAsState()
  val volume by AuraBus.volume.collectAsState()
  val reachable by AuraBus.phoneReachable.collectAsState()
  val lastAudio by AuraBus.lastAudioPath.collectAsState()
  val playing by AuraBus.playing.collectAsState()
  val listState = rememberTransformingLazyColumnState()

  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(isRecording) {
    while (isRecording) {
      now = System.currentTimeMillis()
      delay(500)
    }
  }

  // Défilement à la lunette : TransformingLazyColumn gère l'entrée rotative par défaut.
  ScreenScaffold(scrollState = listState) { padding ->
    TransformingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
      // Onglet du bridge où partent la voix et le texte (sous l'heure).
      item {
        val title = if (newConv?.status == NewConvStatus.PENDING) "Nouvelle conversation…" else activeConvTitle(convs)
        Text(
          title,
          color = AuraColors.muted,
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
      }
      newConv?.takeIf { it.status != NewConvStatus.PENDING }?.let { failed ->
        item {
          Text(
            if (failed.status == NewConvStatus.PHONE_UNREACHABLE) "Téléphone injoignable" else "PC injoignable, conversation non créée",
            color = AuraColors.warn,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
          )
        }
      }
      if (reachable == false) {
        item {
          Text(
            "Téléphone injoignable : vérifie le Bluetooth et l'app Aura du téléphone",
            color = AuraColors.warn,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
          )
        }
      }
      item {
        val label = if (isRecording) {
          val s = ((now - recordStartedAt) / 1000).coerceAtLeast(0)
          val max = VoiceRecorder.MAX_DURATION_MS / 1000
          "Enregistrement %d:%02d / %d:%02d".format(s / 60, s % 60, max / 60, max % 60)
        } else {
          StateLabels.label(conversation.state) ?: "Appuie pour parler"
        }
        val color = when {
          isRecording -> AuraColors.recording
          StateLabels.isError(conversation.state) -> AuraColors.warn
          else -> AuraColors.muted
        }
        Text(label, color = color, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
      }
      item {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          AuraHalo(
            active = isRecording || conversation.state == States.THINKING,
            color = if (isRecording) AuraColors.recording else AuraColors.accent,
            modifier = Modifier.size(120.dp),
          )
          FilledIconButton(
            onClick = onMic,
            modifier = Modifier.size(84.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
              containerColor = if (isRecording) AuraColors.recording else AuraColors.accent,
              contentColor = AuraColors.onAccent,
            ),
          ) {
            Icon(
              painter = painterResource(if (isRecording) R.drawable.ic_send else R.drawable.ic_mic),
              contentDescription = if (isRecording) "Envoyer" else "Parler",
              modifier = Modifier.size(40.dp),
            )
          }
        }
      }
      if (!conversation.stateText.isNullOrBlank() && StateLabels.isError(conversation.state)) {
        item {
          Text(conversation.stateText.orEmpty(), color = AuraColors.warn, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
      }
      if (conversation.reply.isNotBlank()) {
        item {
          Card(modifier = Modifier.fillMaxWidth()) {
            Text(conversation.reply, style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
      if (lastAudio != null || playing) {
        item {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            FilledTonalButton(onClick = onReplay, enabled = lastAudio != null, modifier = Modifier.weight(1f)) {
              Icon(painterResource(R.drawable.ic_replay), contentDescription = "Rejouer", modifier = Modifier.size(20.dp))
            }
            FilledTonalButton(onClick = onStopAudio, enabled = playing, modifier = Modifier.weight(1f)) {
              Icon(painterResource(R.drawable.ic_stop), contentDescription = "Stop audio", modifier = Modifier.size(20.dp))
            }
          }
        }
      }
      item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
          FilledTonalButton(onClick = onQuieter, modifier = Modifier.size(width = 52.dp, height = 44.dp)) {
            Text("−", style = MaterialTheme.typography.titleLarge)
          }
          // Taper le libellé : écran volume, réglable à la lunette.
          Box(
            Modifier.weight(1f).height(44.dp).clickable(onClickLabel = "Régler le volume", onClick = onVolume),
            contentAlignment = Alignment.Center,
          ) {
            Text(volume?.let(VolumeLogic::label) ?: "Volume", color = AuraColors.muted, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
          }
          FilledTonalButton(onClick = onLouder, modifier = Modifier.size(width = 52.dp, height = 44.dp)) {
            Text("+", style = MaterialTheme.typography.titleLarge)
          }
        }
      }
      item {
        val active = convs?.let { l -> l.convs.firstOrNull { it.id == l.active } }
        Button(
          onClick = onConvs,
          enabled = !isRecording,
          modifier = Modifier.fillMaxWidth(),
          icon = { Icon(painterResource(R.drawable.ic_chats), contentDescription = null) },
          label = { Text("Reprendre une conversation", maxLines = 2) },
          secondaryLabel = {
            Text(active?.let { "→ ${convTitle(it)}" } ?: "→ la plus récente", maxLines = 1, overflow = TextOverflow.Ellipsis)
          },
        )
      }
      item {
        Button(
          onClick = onNewConv,
          enabled = !isRecording,
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.filledTonalButtonColors(),
          label = { Text("＋ Nouvelle conversation") },
        )
      }
      QUICK_REPLIES.forEach { q ->
        item {
          FilledTonalButton(onClick = { onQuick(q) }, enabled = !isRecording, modifier = Modifier.fillMaxWidth()) {
            Text(q)
          }
        }
      }
      item {
        Button(
          onClick = onKeyboard,
          enabled = !isRecording,
          modifier = Modifier.fillMaxWidth(),
          icon = { Icon(painterResource(R.drawable.ic_keyboard), contentDescription = null) },
          label = { Text("Taper") },
        )
      }
      item {
        Button(
          onClick = onSettings,
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.outlinedButtonColors(),
          icon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
          label = { Text("Réglages") },
        )
      }
    }
  }
}

fun convTitle(c: ConvItem): String = c.title.ifBlank { "Conversation ${c.id.take(6)}" }

/** Titre de l'onglet choisi (« ● » s'il travaille), « la plus récente » sans choix. */
fun activeConvTitle(list: ConvList?): String {
  val active = list?.let { l -> l.convs.firstOrNull { it.id == l.active } } ?: return "la plus récente"
  return (if (active.busy) "● " else "") + convTitle(active)
}

/**
 * La liste des onglets ouverts du bridge (un fil par conversation), relayée par le téléphone.
 * Choisir = la voix et le texte de la montre partent dans cet onglet jusqu'à nouvel ordre ;
 * « La plus récente » rend le comportement d'avant (le téléphone choisit).
 */
@Composable
fun ConversationsScreen(
  onRequest: suspend () -> Boolean,
  onNew: () -> Unit,
  onPick: suspend (String?) -> Boolean,
  onPicked: () -> Unit,
) {
  val list by AuraBus.convs.collectAsState()
  val listState = rememberTransformingLazyColumnState()
  val scope = rememberCoroutineScope()
  var attempt by remember { mutableIntStateOf(0) }
  var unreachable by remember { mutableStateOf(false) }
  var picking by remember { mutableStateOf(false) }
  var pickError by remember { mutableStateOf(false) }
  // Demande à l'ouverture (et à chaque « Réessayer ») ; sans réponse en 5 s, le téléphone est déclaré injoignable.
  LaunchedEffect(attempt) {
    unreachable = false
    val sentAt = System.currentTimeMillis()
    if (!onRequest()) {
      unreachable = true
      return@LaunchedEffect
    }
    delay(CONVS_TIMEOUT_MS)
    if (AuraBus.convsReceivedAt.value < sentAt) unreachable = true
  }
  // L'écran ne se ferme qu'une fois le choix reçu par le S22.
  val pick: (String?) -> Unit = { id ->
    if (!picking) {
      picking = true
      pickError = false
      scope.launch {
        val ok = onPick(id)
        picking = false
        if (ok) onPicked() else pickError = true
      }
    }
  }
  ScreenScaffold(scrollState = listState) { padding ->
    TransformingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
      item {
        Text("Conversations", style = MaterialTheme.typography.titleMedium, color = AuraColors.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
      }
      if (unreachable) {
        item {
          Text("Téléphone injoignable", color = AuraColors.warn, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        item {
          Button(
            onClick = { attempt++ },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.filledTonalButtonColors(),
            label = { Text("Réessayer") },
          )
        }
      }
      if (pickError) {
        item {
          Text("Téléphone injoignable : choix non envoyé", color = AuraColors.warn, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
      }
      item {
        Button(
          onClick = onNew,
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.outlinedButtonColors(),
          label = { Text("＋ Nouvelle conversation") },
        )
      }
      item {
        Button(
          onClick = { pick(null) },
          enabled = !picking,
          modifier = Modifier.fillMaxWidth(),
          colors = if (list?.active == null) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
          label = { Text("✦ La plus récente") },
          secondaryLabel = { Text("automatique") },
        )
      }
      val l = list
      when {
        l == null -> if (!unreachable) {
          item {
            Text("Chargement…", color = AuraColors.muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
          }
        }
        else -> {
          if (l.pcOffline) {
            item {
              Text("PC injoignable : la liste peut dater", color = AuraColors.warn, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
          }
          if (l.convs.isEmpty()) {
            item {
              Text("Aucune conversation ouverte", color = AuraColors.muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
          }
          l.convs.forEach { c ->
            item {
              Button(
                onClick = { pick(c.id) },
                enabled = !picking,
                modifier = Modifier.fillMaxWidth(),
                colors = if (c.id == l.active) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                label = { Text(convTitle(c), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = { Text(if (c.busy) "● Aura travaille" else ago(c.updated), maxLines = 1) },
              )
            }
          }
        }
      }
    }
  }
}

private const val CONVS_TIMEOUT_MS = 5_000L

/**
 * Écran volume : grand niveau + boost, − / +, et la lunette règle le volume (un cran = un pas, tic à chaque
 * changement ; pas de tic en butée). Pas d'échantillon sonore.
 */
@Composable
fun VolumeScreen(onLouder: () -> Boolean, onQuieter: () -> Boolean) {
  val level by AuraBus.volume.collectAsState()
  val context = LocalContext.current
  val focus = remember { FocusRequester() }
  val steps = remember { RotarySteps(lowRes = context.packageManager.hasSystemFeature(RotarySteps.LOW_RES_FEATURE)) }
  val step: (Boolean) -> Unit = { up -> if (if (up) onLouder() else onQuieter()) Haptics.tick(context) }
  LaunchedEffect(Unit) { focus.requestFocus() }
  ScreenScaffold { padding ->
    Column(
      Modifier
        .fillMaxSize()
        .padding(padding)
        .onRotaryScrollEvent { e ->
          val n = steps.feed(e.verticalScrollPixels)
          repeat(kotlin.math.abs(n)) { step(n > 0) }
          true
        }
        .focusRequester(focus)
        .focusable(),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      val l = level
      val boost = l?.let { VolumeLogic.effectiveBoost(it.volume, it.max, it.boost) } ?: 0
      Text("Volume", color = AuraColors.muted, style = MaterialTheme.typography.labelMedium)
      Text(
        when {
          l == null -> "…"
          l.volume <= 0 -> "Coupé"
          else -> "${l.volume}/${l.max}"
        },
        style = MaterialTheme.typography.displayMedium,
      )
      Text(
        if (boost > 0) "Boost $boost/${VolumeLogic.BOOST_MAX}" else "Sans boost",
        color = if (boost > 0) AuraColors.accent else AuraColors.muted,
        style = MaterialTheme.typography.labelMedium,
      )
      Row(
        Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilledTonalButton(onClick = { step(false) }, modifier = Modifier.size(width = 60.dp, height = 48.dp)) {
          Text("−", style = MaterialTheme.typography.titleLarge)
        }
        FilledTonalButton(onClick = { step(true) }, modifier = Modifier.size(width = 60.dp, height = 48.dp)) {
          Text("+", style = MaterialTheme.typography.titleLarge)
        }
      }
      Text("Tourne la lunette", color = AuraColors.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
    }
  }
}

private fun ago(updated: Double): String {
  if (updated <= 0) return ""
  val min = ((System.currentTimeMillis() / 1000.0 - updated) / 60).toLong().coerceAtLeast(0)
  return when {
    min < 1 -> "à l'instant"
    min < 60 -> "il y a $min min"
    min < 24 * 60 -> "il y a ${min / 60} h"
    else -> "il y a ${min / 1440} j"
  }
}

/** Le halo d'Aura autour du micro : il respire au repos, tourne et s'avive quand elle écoute ou réfléchit. */
@Composable
fun AuraHalo(active: Boolean, color: Color, modifier: Modifier = Modifier) {
  val t = rememberInfiniteTransition(label = "halo")
  val breath by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(if (active) 700 else 2400), RepeatMode.Reverse), label = "breath")
  val turn by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (active) 1600 else 9000, easing = LinearEasing)), label = "turn")
  Canvas(modifier) {
    val r = size.minDimension / 2
    drawCircle(
      Brush.radialGradient(listOf(color.copy(alpha = 0.45f * breath), Color.Transparent), center, r),
      radius = r,
    )
    drawCircle(color.copy(alpha = 0.35f + 0.4f * breath), radius = r * 0.86f, style = Stroke(width = 2.dp.toPx()))
    // trois étincelles qui orbitent
    for (i in 0 until 3) {
      val a = Math.toRadians((turn + i * 120f).toDouble())
      val p = Offset(center.x + (r * 0.86f * cos(a)).toFloat(), center.y + (r * 0.86f * sin(a)).toFloat())
      drawCircle(Color.White.copy(alpha = 0.5f + 0.5f * breath), radius = (2.5f + 1.5f * breath).dp.toPx(), center = p)
    }
  }
}
