package dev.aura.mobile

import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.aura.mobile.data.AuraBus
import dev.aura.mobile.data.AuraStore
import dev.aura.mobile.health.StepsTracker
import dev.aura.mobile.notify.Notifier
import dev.aura.mobile.wear.PhoneLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AuraApp : Application() {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val store by lazy { AuraStore(this) }
  val phone by lazy { PhoneLink(this) }

  override fun onCreate() {
    super.onCreate()
    Notifier.createChannels(this)
    scope.launch {
      AuraBus.settings.value = store.settingsNow()
      AuraBus.lastAudioPath.value = store.lastAudio()?.takeIf { java.io.File(it).exists() }
      AuraBus.status.value = store.status()
      store.lastReply()?.let { (id, text) ->
        if (AuraBus.conversation.value.reply.isEmpty()) AuraBus.setReply(id, text, final = true)
      }
      // Le DataItem a pu changer pendant que l'app était arrêtée : on relit l'état courant.
      phone.fetchSettings()?.let {
        store.saveSettings(it)
        AuraBus.settings.value = it
      }
    }
    scope.launch { StepsTracker.registerPassive(this@AuraApp) }
  }

  companion object {
    fun get(context: Context): AuraApp = context.applicationContext as AuraApp

    /** Vrai si une activité de l'app est visible. */
    val isForeground: Boolean
      get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
  }
}
