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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.aura.mobile.data.AuraBus
import dev.aura.mobile.data.PendingConfirm
import dev.aura.mobile.protocol.ConfirmRequest
import dev.aura.mobile.wear.ConfirmCenter
import kotlinx.coroutines.delay

/** Demande d'accord plein écran : réveille l'écran, compte à rebours, ✓ / ✗. */
class ConfirmActivity : ComponentActivity() {
  private var target by mutableStateOf<PendingConfirm?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    target = fromIntent(intent)
    if (target == null) {
      finish()
      return
    }
    setContent {
      AuraTheme {
        val fromIntent = target ?: return@AuraTheme
        val bus by AuraBus.confirm.collectAsState()
        val settled by ConfirmCenter.settled.collectAsState()
        val id = fromIntent.request.actionId
        // La demande vit dans AuraBus ; les extras servent de secours si le processus a redémarré entre-temps.
        val current = when {
          id in settled -> null
          bus?.request?.actionId == id -> bus
          fromIntent.deadlineMs > System.currentTimeMillis() -> fromIntent
          else -> null
        }
        LaunchedEffect(current == null) { if (current == null) finish() }
        current?.let { ConfirmScreen(it, ::answer) }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    // Nouvelle demande alors qu'un écran est déjà ouvert (singleTop) : on affiche la plus récente.
    fromIntent(intent)?.let { target = it }
  }

  private fun answer(actionId: String, ok: Boolean) {
    ConfirmCenter.respond(this, actionId, ok)
    finish()
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
private fun ConfirmScreen(pending: PendingConfirm, onAnswer: (String, Boolean) -> Unit) {
  val total = pending.request.timeoutS * 1000L
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(pending.request.actionId) {
    while (true) {
      now = System.currentTimeMillis()
      if (now >= pending.deadlineMs) {
        // Expiration : on répond non.
        onAnswer(pending.request.actionId, false)
        break
      }
      delay(250)
    }
  }
  val remaining = (pending.deadlineMs - now).coerceAtLeast(0)
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
      Text("Confirmer ? ${(remaining + 999) / 1000} s", style = MaterialTheme.typography.labelMedium, color = AuraColors.muted)
      Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        Text(
          pending.request.summary.ifBlank { "Action demandée par Aura" },
          style = MaterialTheme.typography.bodyMedium,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AnswerButton(ok = false) { onAnswer(pending.request.actionId, false) }
        AnswerButton(ok = true) { onAnswer(pending.request.actionId, true) }
      }
    }
  }
}

@Composable
private fun AnswerButton(ok: Boolean, onClick: () -> Unit) {
  FilledIconButton(
    onClick = onClick,
    modifier = Modifier.size(60.dp),
    colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (ok) AuraColors.ok else AuraColors.ko, contentColor = AuraColors.onAccent),
  ) {
    Icon(painterResource(if (ok) R.drawable.ic_check else R.drawable.ic_close), contentDescription = if (ok) "Oui" else "Non", modifier = Modifier.size(32.dp))
  }
}
