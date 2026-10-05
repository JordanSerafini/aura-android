package dev.aura.mobile.cmd

import android.content.Context
import dev.aura.mobile.AuraApp
import dev.aura.mobile.health.Battery
import dev.aura.mobile.health.HeartRate
import dev.aura.mobile.health.HeartRateResult
import dev.aura.mobile.health.Perms
import dev.aura.mobile.health.StepsResult
import dev.aura.mobile.health.StepsTracker
import dev.aura.mobile.notify.Haptics
import dev.aura.mobile.notify.Notifier
import dev.aura.mobile.protocol.CmdMessage
import dev.aura.mobile.protocol.CmdResult
import dev.aura.mobile.protocol.Results
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Exécute un `/aura/cmd` et rend le `/aura/cmd_result` correspondant. */
object CommandHandler {
  suspend fun handle(context: Context, msg: CmdMessage): CmdResult = runCatching {
    when (msg.cmd) {
      "notify" -> notify(context, msg)
      "vibrate" -> vibrate(context, msg)
      "heart_rate" -> heartRate(context, msg)
      "steps" -> steps(context, msg)
      "battery" -> {
        val (percent, charging) = Battery.read(context)
        Results.ok(msg.reqId, "percent" to percent, "charging" to charging)
      }
      else -> Results.error(msg.reqId, "unsupported")
    }
  }.getOrElse { Results.error(msg.reqId, it.message ?: it.javaClass.simpleName) }

  private fun param(msg: CmdMessage, key: String): String? = (msg.params[key] as? JsonPrimitive)?.contentOrNull

  private fun notify(context: Context, msg: CmdMessage): CmdResult {
    if (!Perms.granted(context, Perms.NOTIFICATIONS)) return Results.permission(msg.reqId, Perms.Codes.NOTIFICATIONS)
    val vibrate = (msg.params["vibrate"] as? JsonPrimitive)?.booleanOrNull ?: true
    val shown = Notifier.showNotify(context, param(msg, "title") ?: "Aura", param(msg, "text") ?: "", silent = !vibrate)
    if (!shown) return Results.permission(msg.reqId, Perms.Codes.NOTIFICATIONS)
    if (vibrate) Haptics.vibrate(context, Haptics.SHORT)
    return Results.ok(msg.reqId, "shown" to true)
  }

  private fun vibrate(context: Context, msg: CmdMessage): CmdResult {
    val pattern = param(msg, "pattern") ?: Haptics.SHORT
    if (!Haptics.isKnown(pattern)) return Results.error(msg.reqId, "bad_pattern")
    if (!Haptics.vibrate(context, pattern)) return Results.error(msg.reqId, "no_vibrator")
    return Results.ok(msg.reqId, "pattern" to pattern)
  }

  private suspend fun heartRate(context: Context, msg: CmdMessage): CmdResult {
    if (!Perms.granted(context, Perms.heartRate)) return Results.permission(msg.reqId, Perms.Codes.HEART_RATE)
    // App fermée : Android 16 exige en plus la permission de lecture en arrière-plan.
    if (!AuraApp.isForeground && !Perms.granted(context, Perms.heartRateBackground)) {
      return Results.permission(msg.reqId, Perms.Codes.HEART_RATE_BACKGROUND)
    }
    return when (val r = HeartRate.measure(context)) {
      is HeartRateResult.Ok -> Results.ok(msg.reqId, "bpm" to r.bpm)
      is HeartRateResult.Error -> Results.error(msg.reqId, r.code)
    }
  }

  private suspend fun steps(context: Context, msg: CmdMessage): CmdResult = when (val r = StepsTracker.stepsToday(context)) {
    is StepsResult.Ok -> Results.ok(msg.reqId, "steps_today" to r.steps)
    is StepsResult.Error -> Results.error(msg.reqId, r.code)
  }
}
