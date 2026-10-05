package dev.aura.mobile.device

import android.util.Log
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/** Reception Data Layer : Play services reveille l'app meme fermee. */
class AuraWearService : WearableListenerService() {

  override fun onCreate() {
    super.onCreate()
    Aura.init(this)
  }

  private fun ensureService() {
    if (!Aura.serviceRunning && Aura.prefs.getBoolean("service_enabled", false)) AuraService.start(this, false)
  }

  override fun onMessageReceived(event: MessageEvent) {
    Aura.watch.seen()
    val json = try {
      JSONObject(String(event.data, Charsets.UTF_8).ifBlank { "{}" })
    } catch (e: Exception) {
      JSONObject()
    }
    when (event.path) {
      "/aura/ping" -> Aura.pool.execute { Aura.watch.send(event.sourceNodeId, "/aura/pong", JSONObject()) }
      "/aura/pong" -> Aura.watch.onPong()
      "/aura/chat" -> { ensureService(); WatchRelay.onChat(event.sourceNodeId, json) }
      "/aura/confirm_response" -> json.str("action_id")?.let {
        Aura.confirms.resolve(it, if (json.optBoolean("ok")) "accepted" else "refused", "watch")
      }
      "/aura/cmd_result" -> Aura.watch.onCmdResult(json)
      // bouton « Pause » de la tuile : applique ici (meme hors ligne) puis previent le bridge
      "/aura/pause_set" -> {
        ensureService()
        Pause.set(json.optString("mode", PauseMode.OFF), if (json.isNull("until")) 0L else json.optLong("until", 0L), "montre")
      }
      // « Reprendre une conversation » : liste des onglets ouverts, puis le choix de l'utilisateur (§8)
      "/aura/convs_request" -> {
        WatchConvs.heard(event.sourceNodeId, asked = true)
        Aura.wearQ.execute { Aura.watch.send(event.sourceNodeId, "/aura/convs", WatchConvs.reply()) }
      }
      // « Nouvelle conversation » : le bridge cree l'onglet et renvoie `req` ; BridgeClient le selectionne
      "/aura/conv_new" -> {
        val node = event.sourceNodeId
        WatchConvs.heard(node, asked = true)
        ensureService()
        val req = json.str("req")?.takeIf { it.isNotBlank() }?.take(64) ?: WatchConvs.newReq()
        val sent = Aura.bridge.online() && run {
          WatchConvs.requestNew(node, req)
          Aura.bridge.send(JSONObject().put("type", "conv_new").put("req", req))
        }
        if (!sent) {
          WatchConvs.cancelNew()
          val p = WatchConvs.payload().put("error", "offline")
          Aura.wearQ.execute { Aura.watch.send(node, "/aura/convs", p) }
        }
      }
      "/aura/conv_select" -> {
        WatchConvs.heard(event.sourceNodeId, asked = true)
        WatchConvs.select(json.str("conv"))
        Aura.wearQ.execute { Aura.watch.send(event.sourceNodeId, "/aura/convs", WatchConvs.reply()) }
      }
      else -> Log.i(Aura.TAG, "chemin montre ignore : ${event.path}")
    }
  }

  override fun onChannelOpened(channel: ChannelClient.Channel) {
    val path = channel.path
    if (!path.startsWith("/aura/voice/")) return
    Aura.watch.seen()
    ensureService()
    val id = path.removePrefix("/aura/voice/")
    val node = channel.nodeId
    Aura.pool.execute {
      try {
        val bytes = Aura.watch.readChannel(channel)
        WatchRelay.onVoice(node, id, bytes)
      } catch (e: Exception) {
        Log.w(Aura.TAG, "vocal montre illisible", e)
        WatchRelay.stateTo(node, id, "error", "Vocal non reçu par le téléphone")
      }
    }
  }

  override fun onCapabilityChanged(info: CapabilityInfo) {
    if (info.name == WatchLink.CAP_WATCH) Aura.watch.update(info)
  }
}
