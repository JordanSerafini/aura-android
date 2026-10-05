package dev.aura.mobile.ui

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.input.RemoteInputIntentHelper
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import dev.aura.mobile.AuraApp
import dev.aura.mobile.audio.AudioPlayer
import dev.aura.mobile.audio.VoiceRecorder
import dev.aura.mobile.data.AuraBus
import dev.aura.mobile.health.Perms
import dev.aura.mobile.notify.Haptics
import dev.aura.mobile.notify.Notifier
import dev.aura.mobile.protocol.ChatMessage
import dev.aura.mobile.protocol.ConvNew
import dev.aura.mobile.protocol.ConvSelect
import dev.aura.mobile.protocol.NewConv
import dev.aura.mobile.protocol.NewConvLogic
import dev.aura.mobile.protocol.NewConvStatus
import dev.aura.mobile.protocol.Paths
import dev.aura.mobile.protocol.Protocol
import dev.aura.mobile.protocol.States
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class MainActivity : ComponentActivity() {
  private val app get() = AuraApp.get(this)
  private lateinit var recorder: VoiceRecorder
  private var recordingId: String? = null
  private var watchdogJob: Job? = null
  private var newConvJob: Job? = null
  private var recordAfterPermission = false

  private var isRecording by mutableStateOf(false)
  private var recordStartedAt by mutableLongStateOf(0L)

  private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { info ->
    AuraBus.phoneReachable.value = info.nodes.isNotEmpty()
  }

  /** Volume changé hors de l'app (lunette système, panneau rapide) : le libellé suit, le boost retombe sous le max. */
  private val volumeReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      AudioPlayer.publish(context)
    }
  }

  private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted && recordAfterPermission) startRecording()
    recordAfterPermission = false
  }

  private val firstPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
    if (result[Perms.MIC] == true && recordAfterPermission) startRecording()
    recordAfterPermission = false
  }

  private val keyboard = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
    val text = res.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(REMOTE_INPUT_KEY) }?.toString()?.trim()
    if (!text.isNullOrEmpty()) sendChat(text)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    recorder = VoiceRecorder(this)
    // Recréation (processus tué, config) : l'intent d'origine de la tuile ne doit pas relancer un enregistrement.
    val wantsRecord = savedInstanceState == null && intent?.getBooleanExtra(EXTRA_RECORD, false) == true
    if (savedInstanceState == null) reopenConfirm(intent)
    askFirstPermissions(wantsRecord)
    if (wantsRecord && Perms.granted(this, Perms.MIC)) startRecording()
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (isActive) {
          AuraBus.phoneReachable.value = app.phone.isPhoneReachable()
          delay(30_000)
        }
      }
    }
    setContent {
      AuraTheme {
        AuraNavHost(
          isRecording = isRecording,
          recordStartedAt = recordStartedAt,
          onMic = ::onMic,
          onQuick = ::sendChat,
          onKeyboard = ::openKeyboard,
          onReplay = ::replay,
          onStopAudio = { AudioPlayer.stop() },
          onRequestConvs = ::requestConvs,
          onSelectConv = ::selectConv,
          onNewConv = ::newConversation,
          onLouder = { AudioPlayer.louder(this) },
          onQuieter = { AudioPlayer.quieter(this) },
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    // Tuile, complication, notification : on revient à l'écran principal (même depuis Réglages/Conversations).
    AuraBus.goHome.tryEmit(Unit)
    reopenConfirm(intent)
    if (intent.getBooleanExtra(EXTRA_RECORD, false) && !isRecording) {
      if (Perms.granted(this, Perms.MIC)) startRecording() else requestMicThenRecord()
    }
  }

  override fun onStart() {
    super.onStart()
    AudioPlayer.publish(this)
    Notifier.cancelReply(this)
    Wearable.getCapabilityClient(this).addListener(capabilityListener, Paths.CAPABILITY_PHONE)
    ContextCompat.registerReceiver(this, volumeReceiver, IntentFilter(VOLUME_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED)
    // Titre de l'onglet actif juste dès l'ouverture, pas seulement sur l'écran Conversations.
    app.scope.launch { app.phone.send(Paths.CONVS_REQUEST, Protocol.EMPTY) }
  }

  override fun onStop() {
    unregisterReceiver(volumeReceiver)
    Wearable.getCapabilityClient(this).removeListener(capabilityListener, Paths.CAPABILITY_PHONE)
    super.onStop()
  }

  override fun onDestroy() {
    // L'enregistreur appartient à cette instance : il ne survit jamais à sa destruction (même en recréation),
    // et l'état « recording » ne doit pas rester affiché sans enregistrement derrière.
    recordingId?.let { AuraBus.clearState(it, LocalStates.RECORDING) }
    recordingId = null
    isRecording = false
    recorder.stopQuietly()
    super.onDestroy()
  }

  /** Pastille « N à valider » de la tuile : rouvre la demande d'accord en attente, si la montre la connaît encore. */
  private fun reopenConfirm(intent: Intent?) {
    if (intent?.getBooleanExtra(EXTRA_CONFIRM, false) != true) return
    val pending = AuraBus.confirm.value ?: return
    if (pending.deadlineMs <= System.currentTimeMillis()) return
    startActivity(ConfirmActivity.intent(this, pending))
  }

  private fun askFirstPermissions(wantsRecord: Boolean) {
    val missing = listOf(Perms.MIC, Perms.NOTIFICATIONS).filterNot { Perms.granted(this, it) }
    if (missing.isEmpty()) return
    recordAfterPermission = wantsRecord
    firstPermissions.launch(missing.toTypedArray())
  }

  private fun requestMicThenRecord() {
    recordAfterPermission = true
    micPermission.launch(Perms.MIC)
  }

  private fun onMic() {
    if (isRecording) stopAndSend() else if (Perms.granted(this, Perms.MIC)) startRecording() else requestMicThenRecord()
  }

  private fun startRecording() {
    if (isRecording) return
    AudioPlayer.stop()
    val id = Protocol.shortId()
    val started = recorder.start(id) { runOnUiThread { if (isRecording) stopAndSend() } }
    if (!started) {
      AuraBus.startLocal(id, LocalStates.RECORD_FAILED)
      return
    }
    recordingId = id
    isRecording = true
    recordStartedAt = System.currentTimeMillis()
    AuraBus.startLocal(id, LocalStates.RECORDING)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    Haptics.vibrate(this, Haptics.SHORT)
  }

  private fun stopAndSend() {
    val id = recordingId ?: return
    recordingId = null
    isRecording = false
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    val file = recorder.stop()
    if (file == null) {
      AuraBus.startLocal(id, LocalStates.RECORD_FAILED)
      return
    }
    Haptics.vibrate(this, Haptics.SHORT)
    AuraBus.startLocal(id, LocalStates.SENDING)
    app.scope.launch { deliver(id) { app.phone.sendVoice(id, file) }.also { if (it) file.delete() } }
  }

  /** Demande la liste des onglets ; false si le message n'a pas pu partir vers le S22. */
  private suspend fun requestConvs(): Boolean =
    app.scope.async { app.phone.send(Paths.CONVS_REQUEST, Protocol.EMPTY) }.await()

  /** Choix d'un onglet (null = la plus récente) ; true seulement si le S22 a reçu le choix. */
  private suspend fun selectConv(id: String?): Boolean = app.scope.async {
    val ok = app.phone.send(Paths.CONV_SELECT, Protocol.encode(ConvSelect(id)))
    if (ok) {
      AuraBus.convs.update { it?.copy(active = id) }
      Haptics.vibrate(app, Haptics.SHORT)
    } else {
      AuraBus.phoneReachable.value = false
    }
    ok
  }.await()

  /**
   * Repartir de zéro : onglet neuf au bridge (créé et sélectionné par le S22). L'ancienne réponse n'est vidée
   * qu'à la confirmation (`/aura/convs` avec `created == req`, cf. AuraListenerService.onConvs).
   */
  private fun newConversation() {
    AudioPlayer.stop()
    val req = Protocol.shortId()
    val pending = NewConv(req)
    AuraBus.newConv.value = pending
    newConvJob?.cancel()
    newConvJob = app.scope.launch {
      if (!app.phone.send(Paths.CONV_NEW, Protocol.encode(ConvNew(req)))) {
        AuraBus.newConv.compareAndSet(pending, pending.copy(status = NewConvStatus.PHONE_UNREACHABLE))
        AuraBus.phoneReachable.value = false
      } else {
        val settled = withTimeoutOrNull(NewConvLogic.TIMEOUT_MS) {
          AuraBus.newConv.first { it?.req != req || it.status != NewConvStatus.PENDING }
        }
        if (settled == null) AuraBus.newConv.compareAndSet(pending, pending.copy(status = NewConvStatus.PC_OFFLINE))
      }
      // L'échec reste affiché un moment, puis le titre de l'onglet actif revient.
      delay(NEW_CONV_ERROR_SHOWN_MS)
      AuraBus.newConv.update { if (it?.req == req && it.status != NewConvStatus.PENDING) null else it }
    }
  }

  private fun sendChat(text: String) {
    val id = Protocol.shortId()
    AudioPlayer.stop()
    AuraBus.startLocal(id, LocalStates.SENDING)
    app.scope.launch { deliver(id) { app.phone.send(Paths.CHAT, Protocol.encode(ChatMessage(id, text))) } }
  }

  /** Envoie puis surveille : sans nouvelle du téléphone au bout de 30 s, on le signale. */
  private suspend fun deliver(id: String, send: suspend () -> Boolean): Boolean {
    val ok = send()
    val current = AuraBus.conversation.value
    if (current.id != id) return ok
    if (!ok) {
      AuraBus.setState(id, LocalStates.PHONE_UNREACHABLE)
      AuraBus.phoneReachable.value = false
      return false
    }
    if (current.state == LocalStates.SENDING) AuraBus.setState(id, States.SENT)
    watchdogJob?.cancel()
    watchdogJob = app.scope.launch {
      delay(NO_NEWS_MS)
      val c = AuraBus.conversation.value
      if (c.id == id && c.state in setOf(LocalStates.SENDING, States.SENT) && System.currentTimeMillis() - c.updatedAt >= NO_NEWS_MS) {
        AuraBus.setState(id, LocalStates.NO_ANSWER)
      }
    }
    return true
  }

  private fun openKeyboard() {
    val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY).setLabel("Message pour Aura").build()
    val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
    RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
    keyboard.launch(intent)
  }

  private fun replay() {
    val path = AuraBus.lastAudioPath.value ?: return
    AudioPlayer.play(this, File(path))
  }

  companion object {
    const val EXTRA_RECORD = "record"
    const val EXTRA_CONFIRM = "open_confirm"
    private const val REMOTE_INPUT_KEY = "aura_text"
    private const val NO_NEWS_MS = 30_000L
    private const val NEW_CONV_ERROR_SHOWN_MS = 20_000L
    private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
  }
}
