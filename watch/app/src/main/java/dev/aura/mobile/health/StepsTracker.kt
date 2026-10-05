package dev.aura.mobile.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.concurrent.futures.await
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import dev.aura.mobile.AuraApp
import dev.aura.mobile.data.StepBaseline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

sealed interface StepsResult {
  data class Ok(val steps: Long) : StepsResult
  data class Error(val code: String) : StepsResult
}

/**
 * Pas du jour : Health Services PassiveMonitoringClient (STEPS_DAILY, mémorisé par [StepsPassiveService]),
 * repli sur le capteur TYPE_STEP_COUNTER avec une base quotidienne.
 */
object StepsTracker {
  private const val TAG = "AuraSteps"

  fun today(): String = LocalDate.now().toString()

  /** Enregistre le service passif (idempotent). Sans permission ACTIVITY_RECOGNITION : ne fait rien. */
  suspend fun registerPassive(context: Context): Boolean {
    if (!Perms.granted(context, Perms.ACTIVITY)) return false
    return runCatching {
      val client = HealthServices.getClient(context).passiveMonitoringClient
      val caps = client.getCapabilitiesAsync().await()
      if (DataType.STEPS_DAILY !in caps.supportedDataTypesPassiveMonitoring) return false
      val config = PassiveListenerConfig.builder().setDataTypes(setOf(DataType.STEPS_DAILY)).build()
      client.setPassiveListenerServiceAsync(StepsPassiveService::class.java, config).await()
      true
    }.onFailure { Log.w(TAG, "registerPassive", it) }.getOrDefault(false)
  }

  suspend fun stepsToday(context: Context): StepsResult {
    if (!Perms.granted(context, Perms.ACTIVITY)) return StepsResult.Error("permission:" + Perms.Codes.ACTIVITY)
    val store = AuraApp.get(context).store
    val day = today()

    // 1. Health Services : on force la remise des données en attente, puis on relit la valeur mémorisée.
    if (registerPassive(context)) {
      runCatching { HealthServices.getClient(context).passiveMonitoringClient.flushAsync().await() }
      repeat(10) {
        store.passiveSteps()?.let { (d, v) -> if (d == day) return StepsResult.Ok(v) }
        delay(300)
      }
    }
    store.passiveSteps()?.let { (d, v) -> if (d == day) return StepsResult.Ok(v) }

    // 2. Repli : compteur matériel cumulé depuis le démarrage + base du jour.
    val counter = readStepCounter(context) ?: return StepsResult.Error("unavailable")
    val (steps, baseline) = StepBaseline.compute(store.stepBaseline(), day, counter)
    store.saveStepBaseline(baseline)
    return StepsResult.Ok(steps)
  }

  private suspend fun readStepCounter(context: Context): Long? {
    val sm = context.getSystemService(SensorManager::class.java) ?: return null
    val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
    val value = CompletableDeferred<Long>()
    val listener = object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        value.complete(event.values[0].toLong())
      }

      override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    return try {
      if (!sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) return null
      withTimeoutOrNull(5_000) { value.await() }
    } catch (e: SecurityException) {
      Log.w(TAG, "step counter", e)
      null
    } finally {
      sm.unregisterListener(listener)
    }
  }
}
