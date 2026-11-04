package dev.aura.mobile.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.aura.mobile.R
import dev.aura.mobile.AuraApp
import dev.aura.mobile.data.PendingConfirm
import dev.aura.mobile.notify.Haptics
import dev.aura.mobile.protocol.ConfirmFlow
import dev.aura.mobile.protocol.ConfirmRequest
import dev.aura.mobile.wear.ConfirmCenter
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Demande d'accord plein écran : réveille l'écran, compte à rebours, ✓ / ✗.
 *
 * 0.2.0 : l'écran attend que la réponse soit partie (« Envoi… », boutons désactivés). Échec = vibration longue,
 * « Téléphone injoignable, réponds sur le téléphone », boutons réactivés tant que le délai court ; succès = fermeture.
 * Logique : ConfirmFlow (pure, testée).
 */
class ConfirmActivity : ComponentActivity() {
  private var target by mutableStateOf<PendingConfirm?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    target = resolve(intent)
    if (target == null) {
      finish()
      return
    }
    setContent {
      AuraTheme {
        val pending = target ?: return@AuraTheme
        val settled by ConfirmCenter.settled.collectAsState()
        val id = pending.request.actionId
        key(id) {
          ConfirmScreen(pending, settledElsewhere = id in settled, onClose = ::finish)
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    // Nouvelle demande alors qu'un écran est déjà ouvert (singleTop) : on affiche la plus récente.
    resolve(intent)?.let { target = it }
  }

  /** La demande telle que la montre la connaît (AuraBus), à défaut les extras ; null si réglée ou échue. */
  private fun resolve(intent: Intent?): PendingConfirm? {
    val fromExtras = fromIntent(intent) ?: return null
    val id = fromExtras.request.actionId
    if (id in ConfirmCenter.settled.value) return null
    val pending = ConfirmCenter.pending(id) ?: fromExtras
    return pending.takeIf { it.deadlineMs > System.currentTimeMillis() }
  }

  companion object {
    private const val EXTRA_ACTION_ID = "action_id"
    private const val EXTRA_SUMMARY = "summary"
    private const val EXTRA_TIMEOUT = "timeout_s"
    private const val EXTRA_DEADLINE = "deadline_ms"

    fun intent(context: Context, pending: PendingConfirm): Intent =
      Intent(context, ConfirmActivity::class.java)
        .putExtra(EXTRA_ACTION_ID, pending.request.actionId)
        .putExtra(EXTRA_SUMMARY, pending.request.summary)
        .putExtra(EXTRA_TIMEOUT, pending.request.timeoutS)
        .putExtra(EXTRA_DEADLINE, pending.deadlineMs)

    private fun fromIntent(intent: Intent?): PendingConfirm? {
      val id = intent?.getStringExtra(EXTRA_ACTION_ID) ?: return null
      val request = ConfirmRequest(id, intent.getStringExtra(EXTRA_SUMMARY).orEmpty(), intent.getIntExtra(EXTRA_TIMEOUT, 30))
      return PendingConfirm(request, intent.getLongExtra(EXTRA_DEADLINE, 0L))
    }
  }
}

@Composable
private fun ConfirmScreen(pending: PendingConfirm, settledElsewhere: Boolean, onClose: () -> Unit) {
  val context = LocalContext.current
  val app = AuraApp.get(context)
  val id = pending.request.actionId
  val total = pending.request.timeoutS * 1000L
  val scope = rememberCoroutineScope()
  var flow by remember { mutableStateOf<ConfirmFlow.State>(ConfirmFlow.Ready) }
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

  fun dispatch(event: ConfirmFlow.Event) {
    val t = ConfirmFlow.next(flow, event)
    flow = t.state
    t.effects.forEach { effect ->
      when (effect) {
        // l'envoi vit dans le scope de l'app : il se termine (et règle la demande) même si l'écran disparaît
        is ConfirmFlow.Send -> scope.launch {
          val outcome = app.scope.async { ConfirmCenter.respondNow(app, id, effect.ok) }.await()
          dispatch(ConfirmFlow.Result(outcome))
        }
        ConfirmFlow.FailureAlert -> Haptics.vibrate(context, Haptics.LONG)
        ConfirmFlow.LostNotice -> ConfirmCenter.lost(app, pending)
        ConfirmFlow.ExpiryRefusal -> ConfirmCenter.expire(app, id)
      }
    }
  }

  LaunchedEffect(settledElsewhere) { if (settledElsewhere) dispatch(ConfirmFlow.SettledElsewhere) }
  LaunchedEffect(flow == ConfirmFlow.Closed) { if (flow == ConfirmFlow.Closed) onClose() }
  LaunchedEffect(id) {
    while (true) {
      now = System.currentTimeMillis()
      if (now >= pending.deadlineMs) {
        // Expiration : refus automatique, sauf si une réponse est en cours d'envoi (son résultat décide).
        dispatch(ConfirmFlow.Expired)
        break
      }
      delay(250)
    }
  }
  val remaining = (pending.deadlineMs - now).coerceAtLeast(0)
  val enabled = ConfirmFlow.buttonsEnabled(flow)
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(
      progress = { if (total > 0) remaining.toFloat() / total else 0f },
      modifier = Modifier.fillMaxSize().padding(2.dp),
    )
    Column(
      Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 22.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Text(
        ConfirmFlow.headline(flow, remaining),
        style = MaterialTheme.typography.labelMedium,
        color = if (flow is ConfirmFlow.Failed) AuraColors.warn else AuraColors.muted,
        textAlign = TextAlign.Center,
      )
      Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        Text(
          pending.request.summary.ifBlank { "Action demandée par Aura" },
          style = MaterialTheme.typography.bodyMedium,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AnswerButton(ok = false, enabled = enabled) { dispatch(ConfirmFlow.Tap(false)) }
        AnswerButton(ok = true, enabled = enabled) { dispatch(ConfirmFlow.Tap(true)) }
      }
    }
  }
}

@Composable
private fun AnswerButton(ok: Boolean, enabled: Boolean, onClick: () -> Unit) {
  FilledIconButton(
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier.size(60.dp),
    colors = IconButtonDefaults.filledIconButtonColors(
      containerColor = if (ok) AuraColors.ok else AuraColors.ko,
      contentColor = AuraColors.onStatus,
    ),
  ) {
    Icon(painterResource(if (ok) R.drawable.ic_check else R.drawable.ic_close), contentDescription = if (ok) "Oui" else "Non", modifier = Modifier.size(32.dp))
  }
}
