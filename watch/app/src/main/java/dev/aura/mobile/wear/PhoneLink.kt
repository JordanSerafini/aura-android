package dev.aura.mobile.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import dev.aura.mobile.protocol.Paths
import dev.aura.mobile.protocol.SettingsParser
import dev.aura.mobile.protocol.WatchSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Accès au téléphone appairé (capability `aura_phone`) via le Wearable Data Layer. */
class PhoneLink(private val context: Context) {
  private val capabilityClient get() = Wearable.getCapabilityClient(context)
  private val messageClient get() = Wearable.getMessageClient(context)
  private val channelClient get() = Wearable.getChannelClient(context)
  private val dataClient get() = Wearable.getDataClient(context)

  /** Nœud joignable portant `aura_phone` (le plus proche d'abord), ou null. */
  suspend fun phoneNode(): Node? = runCatching {
    val info = capabilityClient.getCapability(Paths.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await()
    info.nodes.sortedByDescending { it.isNearby }.firstOrNull()
  }.onFailure { Log.w(TAG, "capability", it) }.getOrNull()

  suspend fun isPhoneReachable(): Boolean = phoneNode() != null

  /** Envoie un message au téléphone. Rend false si le téléphone est injoignable. */
  suspend fun send(path: String, payload: ByteArray): Boolean {
    val node = phoneNode() ?: return false
    return sendTo(node.id, path, payload)
  }

  suspend fun sendTo(nodeId: String, path: String, payload: ByteArray): Boolean = runCatching {
    messageClient.sendMessage(nodeId, path, payload).await()
    true
  }.onFailure { Log.w(TAG, "send $path", it) }.getOrDefault(false)

  /**
   * Envoie un fichier audio sur `/aura/voice/<id>` via ChannelClient.
   * Attend la fermeture du flux de sortie (fin du transfert) avant de fermer le canal.
   */
  suspend fun sendVoice(id: String, file: File): Boolean {
    val node = phoneNode() ?: return false
    val client = channelClient
    return runCatching {
      val channel = client.openChannel(node.id, Paths.voice(id)).await()
      val done = CompletableDeferred<Int>()
      val callback = object : ChannelClient.ChannelCallback() {
        override fun onOutputClosed(ch: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
          if (ch.path == channel.path) done.complete(closeReason)
        }

        override fun onChannelClosed(ch: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
          if (ch.path == channel.path) done.complete(closeReason)
        }
      }
      client.registerChannelCallback(channel, callback).await()
      try {
        client.sendFile(channel, Uri.fromFile(file)).await()
        val reason = withTimeoutOrNull(90_000) { done.await() }
        reason == null || reason == ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL
      } finally {
        client.unregisterChannelCallback(channel, callback)
        runCatching { client.close(channel).await() }
      }
    }.onFailure { Log.w(TAG, "sendVoice", it) }.getOrDefault(false)
  }

  /** Relit le DataItem `/aura/settings` courant (null s'il n'existe pas encore). */
  suspend fun fetchSettings(): WatchSettings? = runCatching {
    val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(Paths.SETTINGS).build()
    val items = dataClient.getDataItems(uri).await()
    try {
      items.firstOrNull()?.let { parseSettings(it.data, runCatching { DataMapItem.fromDataItem(it).dataMap }.getOrNull()) }
    } finally {
      items.release()
    }
  }.onFailure { Log.w(TAG, "fetchSettings", it) }.getOrNull()

  companion object {
    private const val TAG = "AuraPhoneLink"

    /** JSON brut d'abord (forme de référence), puis DataMap. */
    fun parseSettings(raw: ByteArray?, map: DataMap?): WatchSettings? {
      SettingsParser.fromJsonBytes(raw)?.let { return it }
      if (map == null) return null
      map.getString("json")?.let { json -> SettingsParser.fromJson(json)?.let { return it } }
      if (!map.containsKey("voice_mode") && !map.containsKey("work_hours")) return null
      val hoursMap = runCatching { map.getDataMap("work_hours") }.getOrNull()
      val hoursJson = runCatching { map.getString("work_hours") }.getOrNull()
      val days = hoursMap?.let {
        runCatching { it.getIntegerArrayList("days")?.toIntArray() }.getOrNull()
          ?: runCatching { it.getLongArray("days")?.map(Long::toInt)?.toIntArray() }.getOrNull()
      }
      return SettingsParser.fromParts(
        voiceMode = map.getString("voice_mode"),
        workHoursJson = hoursJson,
        days = days,
        start = hoursMap?.getString("start"),
        end = hoursMap?.getString("end"),
      )
    }
  }
}
