package dev.aura.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.aura.mobile.AuraApp
import dev.aura.mobile.BuildConfig
import dev.aura.mobile.data.AuraBus
import dev.aura.mobile.health.Perms
import dev.aura.mobile.health.StepsTracker
import dev.aura.mobile.protocol.VoiceMode
import dev.aura.mobile.protocol.VoiceModeLogic
import kotlinx.coroutines.launch
import java.time.LocalDateTime

@Composable
fun SettingsScreen() {
  val context = LocalContext.current
  val settings by AuraBus.settings.collectAsState()
  val reachable by AuraBus.phoneReachable.collectAsState()
  // Compteur incrémenté après chaque demande de permission, pour relire l'état.
  var refresh by remember { mutableIntStateOf(0) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    refresh++
    val app = AuraApp.get(context)
    app.scope.launch { StepsTracker.registerPassive(app) }
  }
  val listState = rememberTransformingLazyColumnState()

  fun granted(p: String) = refresh >= 0 && Perms.granted(context, p)

  ScreenScaffold(scrollState = listState) { padding ->
    TransformingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
      item { ListHeader { Text("Réglages") } }
      item {
        val mode = when (settings.voiceMode) {
          VoiceMode.VOICE -> "voix (toujours lue)"
          VoiceMode.TEXT -> "texte (jamais lue)"
          else -> "auto"
        }
        val speaks = VoiceModeLogic.shouldSpeak(settings, LocalDateTime.now())
        val h = settings.workHours
        Text(
          "Mode voix : $mode\nEn ce moment : " + (if (speaks) "réponses lues" else "texte + vibration") +
            (if (settings.voiceMode == VoiceMode.AUTO) "\nTravail : ${h.start}-${h.end}, jours ${h.days.joinToString(",")}" else "") +
            "\nRéglé depuis le téléphone",
          style = MaterialTheme.typography.bodySmall,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      item {
        Text(
          "Téléphone : " + when (reachable) {
            true -> "connecté"
            false -> "injoignable"
            null -> "…"
          },
          style = MaterialTheme.typography.bodySmall,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      item { ListHeader { Text("Permissions") } }
      item {
        PermissionButton("Micro", granted(Perms.MIC)) { launcher.launch(arrayOf(Perms.MIC)) }
      }
      item {
        PermissionButton("Notifications", granted(Perms.NOTIFICATIONS)) { launcher.launch(arrayOf(Perms.NOTIFICATIONS)) }
      }
      item {
        PermissionButton("Santé (cœur, pas)", granted(Perms.heartRate) && granted(Perms.ACTIVITY)) {
          launcher.launch(arrayOf(Perms.heartRate, Perms.ACTIVITY))
        }
      }
      item {
        // Android exige une demande séparée, après la permission de premier plan.
        val fg = granted(Perms.heartRate)
        PermissionButton("Santé en arrière-plan", granted(Perms.heartRateBackground), enabled = fg) {
          launcher.launch(arrayOf(Perms.heartRateBackground))
        }
      }
      item {
        Text(
          "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
          style = MaterialTheme.typography.labelSmall,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

@Composable
private fun PermissionButton(label: String, granted: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
  Button(
    onClick = onClick,
    enabled = enabled && !granted,
    modifier = Modifier.fillMaxWidth(),
    label = { Text(label) },
    secondaryLabel = { Text(if (granted) "Accordée" else if (enabled) "Toucher pour accorder" else "Accorder d'abord Santé") },
  )
}
