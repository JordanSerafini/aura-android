package dev.aura.mobile.device

import org.json.JSONObject

// Copies conformes des symboles d'Aura.kt / BridgeClient.kt (fichiers Android) dont la logique pure a besoin.
class ActionError(val code: String, message: String, val extra: JSONObject? = null) : Exception(message)
fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)
object BridgeClient { const val CLOSE_BAD_TOKEN = 4401 }
object Aura { const val DEFAULT_URL = "wss://example.invalid/ws" }
