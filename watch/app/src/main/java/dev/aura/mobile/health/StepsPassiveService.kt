package dev.aura.mobile.health

import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import dev.aura.mobile.AuraApp
import kotlinx.coroutines.runBlocking

/** Reçoit STEPS_DAILY de Health Services (même app fermée) et mémorise la dernière valeur du jour. */
class StepsPassiveService : PassiveListenerService() {
  override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
    val latest = dataPoints.getData(DataType.STEPS_DAILY).maxByOrNull { it.endDurationFromBoot } ?: return
    // Rappel sur un thread de travail du service : écriture synchrone courte.
    runBlocking { AuraApp.get(this@StepsPassiveService).store.savePassiveSteps(StepsTracker.today(), latest.value) }
  }
}
