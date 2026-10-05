package dev.aura.mobile.health

import android.content.Context
import android.util.Log
import androidx.concurrent.futures.await
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

sealed interface HeartRateResult {
  data class Ok(val bpm: Int) : HeartRateResult
  data class Error(val code: String) : HeartRateResult
}

/** Mesure ponctuelle via Health Services MeasureClient : première valeur > 0, délai 30 s. */
object HeartRate {
  private const val TAG = "AuraHeartRate"
  const val TIMEOUT_MS = 30_000L

  suspend fun measure(context: Context, timeoutMs: Long = TIMEOUT_MS): HeartRateResult {
    val client = HealthServices.getClient(context).measureClient
    val caps = runCatching { client.getCapabilitiesAsync().await() }.getOrNull()
      ?: return HeartRateResult.Error("unavailable")
    if (DataType.HEART_RATE_BPM !in caps.supportedDataTypesMeasure) return HeartRateResult.Error("unsupported")

    val result = CompletableDeferred<Int>()
    var lastAvailability: Availability? = null
    val callback = object : MeasureCallback {
      override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
        lastAvailability = availability
      }

      override fun onDataReceived(data: DataPointContainer) {
        val bpm = data.getData(DataType.HEART_RATE_BPM).lastOrNull { it.value > 0.0 }?.value ?: return
        result.complete(bpm.roundToInt())
      }
    }
    try {
      client.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
    } catch (e: SecurityException) {
      Log.w(TAG, "register", e)
      return HeartRateResult.Error("permission:" + Perms.Codes.HEART_RATE)
    }
    return try {
      val bpm = withTimeoutOrNull(timeoutMs) { result.await() }
      when {
        bpm != null -> HeartRateResult.Ok(bpm)
        lastAvailability == DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY -> HeartRateResult.Error("off_body")
        else -> HeartRateResult.Error("timeout")
      }
    } finally {
      runCatching { client.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback).await() }
    }
  }
}
