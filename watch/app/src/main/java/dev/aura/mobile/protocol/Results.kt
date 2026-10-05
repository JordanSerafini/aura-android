package dev.aura.mobile.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Construction des `/aura/cmd_result`. */
object Results {
  fun ok(reqId: String, vararg pairs: Pair<String, Any>): CmdResult = CmdResult(reqId, ok = true, result = obj(*pairs))

  fun error(reqId: String, error: String): CmdResult = CmdResult(reqId, ok = false, error = error)

  /**
   * Erreur de permission au format du protocole : `permission:<nom>`, nom court en minuscules comme
   * côté téléphone (`heart_rate`, `heart_rate_background`, `activity_recognition`, `notifications`).
   */
  fun permission(reqId: String, name: String): CmdResult = error(reqId, "permission:$name")

  fun obj(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) ->
      when (v) {
        is Number -> put(k, JsonPrimitive(v))
        is Boolean -> put(k, JsonPrimitive(v))
        else -> put(k, JsonPrimitive(v.toString()))
      }
    }
  }
}
