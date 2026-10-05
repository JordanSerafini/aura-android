package dev.aura.mobile.wear

import android.net.Uri
import android.util.Log
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.aura.mobile.AuraApp
import dev.aura.mobile.audio.AudioPlayer
import dev.aura.mobile.cmd.CommandHandler
import dev.aura.mobile.data.AuraBus
import dev.aura.mobile.data.Conversation
import dev.aura.mobile.notify.Haptics
import dev.aura.mobile.notify.Notifier
import dev.aura.mobile.protocol.CallCard
import dev.aura.mobile.protocol.CallCardLogic
import dev.aura.mobile.protocol.CmdMessage
import dev.aura.mobile.protocol.CmdResult
import dev.aura.mobile.protocol.ConfirmCancel
import dev.aura.mobile.protocol.ConfirmRequest
import dev.aura.mobile.protocol.ConvList
import dev.aura.mobile.protocol.NewConvLogic
import dev.aura.mobile.protocol.NewConvOutcome
import dev.aura.mobile.protocol.NewConvStatus
import dev.aura.mobile.protocol.Paths
import dev.aura.mobile.protocol.Protocol
import dev.aura.mobile.protocol.ReplyMessage
import dev.aura.mobile.protocol.StateMessage
import dev.aura.mobile.protocol.StatusLogic
import dev.aura.mobile.protocol.States
import dev.aura.mobile.protocol.VoiceModeLogic
import dev.aura.mobile.tile.AuraTileService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.tasks.await
import java.io.File
import java.time.LocalDateTime

/**
 * Point d'entrée du Data Layer, actif même app fermée. Les rappels arrivent sur un thread de travail
 * propre au service : les opérations courtes y sont faites de façon synchrone, ce qui garde le service
 * lié (et le processus vivant) jusqu'à la fin du traitement.
 */
class AuraListenerService : WearableListenerService() {
  private val app get() = AuraApp.get(this)

  override fun onMessageReceived(event: MessageEvent) {
    val data = event.data
    when (event.path) {
      Paths.PING -> runBlocking { app.phone.sendTo(event.sourceNodeId, Paths.PONG, Protocol.EMPTY) }
      Paths.PONG -> AuraBus.phoneReachable.value = true
      Paths.STATE -> Protocol.decode<StateMessage>(data)?.let(::onState)
      Paths.REPLY -> Protocol.decode<ReplyMessage>(data)?.let(::onReply)
      Paths.CONFIRM_REQUEST -> Protocol.decode<ConfirmRequest>(data)?.let { ConfirmCenter.onRequest(this, it) }
      Paths.CONFIRM_CANCEL -> Protocol.decode<ConfirmCancel>(data)?.let { ConfirmCenter.onCancel(this, it) }
      Paths.CMD -> onCmd(event.sourceNodeId, data)
      Paths.CONVS -> Protocol.decode<ConvList>(data)?.let(::onConvs)
      Paths.CALL_CARD -> onCallCard(data)
      else -> Log.d(TAG, "chemin ignoré ${event.path}")
    }
  }

  private fun onState(msg: StateMessage) {
    AuraBus.setState(msg.id, msg.state, msg.text)
    if (AuraApp.isForeground) return
    when (msg.state) {
      States.ERROR -> Notifier.showReply(this, "Erreur : " + (msg.text ?: "la demande a échoué"))
      States.OFFLINE -> Notifier.showReply(this, "PC injoignable : " + (msg.text ?: "Aura ne répond pas"))
    }
  }

  /** Fiche d'appel du téléphone : notification + vibration. Texte seul, jamais écrit ailleurs. */
  private fun onCallCard(data: ByteArray?) {
    val card = CallCardLogic.prepare(Protocol.decode<CallCard>(data), System.currentTimeMillis() / 1000) ?: return
    if (Notifier.showCallCard(this, card)) Haptics.vibrate(this, Haptics.DOUBLE)
  }

  /** Liste des onglets (réponse, création d'onglet ou envoi spontané du S22 à chaque changement). */
  private fun onConvs(list: ConvList) {
    AuraBus.convs.value = list
    AuraBus.convsReceivedAt.value = System.currentTimeMillis()
    val pending = AuraBus.newConv.value
    when (NewConvLogic.onList(pending, list)) {
      NewConvOutcome.CREATED -> if (AuraBus.newConv.compareAndSet(pending, null)) {
        // Onglet neuf confirmé : seulement maintenant on vide l'ancienne réponse.
        AuraBus.conversation.value = Conversation()
        AuraBus.lastAudioPath.value = null
        Haptics.vibrate(this, Haptics.SHORT)
      }
      NewConvOutcome.PC_OFFLINE -> AuraBus.newConv.compareAndSet(pending, pending?.copy(status = NewConvStatus.PC_OFFLINE))
      NewConvOutcome.NONE -> Unit
    }
  }

  private fun onReply(msg: ReplyMessage) {
    AuraBus.setReply(msg.id, msg.text, msg.final)
    if (!msg.final) return
    runBlocking { app.store.saveLastReply(msg.id, msg.text) }
    TileService.getUpdater(this).requestUpdate(AuraTileService::class.java)
    if (AuraApp.isForeground) {
      Haptics.vibrate(this, Haptics.SHORT)
    } else {
      // Réponse finale app fermée : notification avec le texte + vibration (celle du canal, sinon manuelle).
      if (!Notifier.showReply(this, msg.text)) Haptics.vibrate(this, Haptics.DOUBLE)
    }
  }

  private fun onCmd(sourceNodeId: String, data: ByteArray?) {
    val msg = Protocol.decode<CmdMessage>(data)
    val result: CmdResult = if (msg == null) {
      // On renvoie au moins le req_id s'il est lisible, pour que le téléphone n'attende pas 35 s pour rien.
      val reqId = runCatching {
        (Protocol.json.parseToJsonElement(data!!.toString(Charsets.UTF_8)) as JsonObject)["req_id"]?.jsonPrimitive?.content
      }.getOrNull().orEmpty()
      CmdResult(reqId = reqId, ok = false, error = "bad_request")
    } else {
      // Synchrone (jusqu'à ~35 s pour heart_rate) : garde le service lié pendant la mesure.
      runBlocking { CommandHandler.handle(this@AuraListenerService, msg) }
    }
    runBlocking { app.phone.sendTo(sourceNodeId, Paths.CMD_RESULT, Protocol.encode(result)) }
  }

  override fun onDataChanged(events: DataEventBuffer) {
    events.forEach { event ->
      val item = event.dataItem
      if (event.type == DataEvent.TYPE_CHANGED && item.uri.path == Paths.STATUS) {
        onStatus(item.data)
        return@forEach
      }
      if (event.type != DataEvent.TYPE_CHANGED || item.uri.path != Paths.SETTINGS) return@forEach
      val map = runCatching { DataMapItem.fromDataItem(item).dataMap }.getOrNull()
      val settings = PhoneLink.parseSettings(item.data, map) ?: return@forEach
      AuraBus.settings.value = settings
      runBlocking { app.store.saveSettings(settings) }
    }
  }

  /** Etat du telephone (pause, confirmations en attente, lien bridge) : garde, puis rafraichit tuile et complication. */
  private fun onStatus(raw: ByteArray?) {
    val status = StatusLogic.parse(raw) ?: return
    AuraBus.status.value = status
    runBlocking { app.store.saveStatus(raw!!.toString(Charsets.UTF_8)) }
    StatusSurfaces.refresh(this)
  }

  override fun onChannelOpened(channel: ChannelClient.Channel) {
    val id = Paths.audioId(channel.path) ?: return
    val dir = File(filesDir, "audio").apply { mkdirs() }
    val file = File(dir, "$id.mp3")
    runBlocking {
      runCatching { Wearable.getChannelClient(this@AuraListenerService).receiveFile(channel, Uri.fromFile(file), false).await() }
        .onFailure { Log.w(TAG, "receiveFile $id", it) }
    }
  }

  override fun onInputClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
    val id = Paths.audioId(channel.path) ?: return
    val client = Wearable.getChannelClient(this)
    runBlocking { runCatching { client.close(channel).await() } }
    val file = File(File(filesDir, "audio"), "$id.mp3")
    if (closeReason != ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL || !file.exists() || file.length() == 0L) {
      Log.w(TAG, "audio $id incomplet (raison $closeReason)")
      file.delete()
      return
    }
    // On ne garde que le dernier audio (pour « rejouer »).
    file.parentFile?.listFiles()?.filter { it != file }?.forEach { it.delete() }
    AuraBus.lastAudioPath.value = file.absolutePath
    runBlocking { app.store.saveLastAudio(file.absolutePath) }
    val speak = VoiceModeLogic.shouldSpeak(AuraBus.settings.value, LocalDateTime.now())
    if (AuraApp.isForeground || speak) AudioPlayer.play(this, file)
  }

  companion object {
    private const val TAG = "AuraListener"
  }
}
