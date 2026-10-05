package dev.aura.mobile.device

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Service de premier plan : garde la WebSocket vers desktop_bridge et le relais montre vivants app fermee.
 * Type connectedDevice (la montre) : pas de plafond de 6 h/jour comme dataSync (Android 15), et
 * autorise au demarrage depuis BOOT_COMPLETED. + location quand la localisation est accordee.
 */
class AuraService : Service() {

  override fun onCreate() {
    super.onCreate()
    Aura.init(this)
    instance = this
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val fromUi = intent?.getBooleanExtra("from_ui", false) ?: false
    if (!startInForeground(fromUi)) {
      stopSelf()
      return START_NOT_STICKY
    }
    Aura.serviceRunning = true
    Aura.bridge.start()
    Aura.watch.startListening()
    PhoneContext.start(this)
    PhoneEvents.start(this)
    Aura.emitState()
    return START_STICKY
  }

  override fun onDestroy() {
    instance = null
    Aura.serviceRunning = false
    Aura.bridge.stop()
    Aura.watch.stopListening()
    PhoneContext.stop(this)
    PhoneEvents.stop(this)
    Aura.emitState()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun startInForeground(fromUi: Boolean): Boolean {
    val notif = buildNotification().build()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      startForeground(Notifs.ID_SERVICE, notif)
      return true
    }
    var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    val loc = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    val bg = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    if (loc && (fromUi || bg)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    try {
      ServiceCompat.startForeground(this, Notifs.ID_SERVICE, notif, type)
    } catch (e: Exception) {
      Log.w(Aura.TAG, "startForeground (type $type) refuse, repli connectedDevice", e)
      try {
        ServiceCompat.startForeground(this, Notifs.ID_SERVICE, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
      } catch (e2: Exception) {
        // Android 12+ : premier plan refuse depuis l'arriere-plan (ForegroundServiceStartNotAllowedException)
        // ou prerequis du type absent : sans ce catch le processus entier tombait (module JS compris)
        Log.e(Aura.TAG, "service de premier plan impossible, arret", e2)
        return false
      }
    }
    return true
  }

  private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

  companion object {
    @Volatile private var instance: AuraService? = null

    fun start(ctx: Context, fromUi: Boolean) {
      Aura.init(ctx)
      Aura.prefs.edit().putBoolean("service_enabled", true).apply()
      val i = Intent(ctx, AuraService::class.java).putExtra("from_ui", fromUi)
      try {
        ContextCompat.startForegroundService(ctx, i)
      } catch (e: Exception) {
        // Android 12+ : demarrage de premier plan interdit depuis l'arriere-plan (hors exemptions)
        Log.w(Aura.TAG, "service non demarre", e)
      }
    }

    fun stop(ctx: Context) {
      Aura.init(ctx)
      Aura.prefs.edit().putBoolean("service_enabled", false).apply()
      ctx.stopService(Intent(ctx, AuraService::class.java))
    }

    fun buildNotification(): NotificationCompat.Builder {
      val ctx = Aura.app
      val link = when (Aura.bridge.status) {
        "online" -> if (Aura.watch.connected()) "Connecté · montre reliée" else "Connecté"
        "connecting" -> "Connexion…"
        "rejected" -> "Jeton refusé : réappairer"
        else -> "Hors ligne, nouvel essai automatique"
      }
      val paused = Pause.active()
      val text = if (paused) "${Pause.describe()} · $link" else link
      return NotificationCompat.Builder(ctx, Notifs.CH_SERVICE)
        .setSmallIcon(R.drawable.ic_aura)
        .setContentTitle(if (paused) "Aura en pause" else if (Aura.bridge.status == "online") "Aura connecté" else "Aura")
        .setContentText(text)
        // arret d'urgence : un appui depuis le rideau de notifications, sans ouvrir l'app
        .addAction(0, if (paused) "▶ Reprendre" else "⏸ Pause 1 h",
          Notifs.receiverIntent(ctx, Notifs.ID_SERVICE + 1, ActionReceiver.ACTION_PAUSE_TOGGLE))
        .setOngoing(true)
        .setSilent(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(Notifs.launchAppIntent(ctx))
    }

    fun refreshNotification() {
      if (instance == null) return
      Notifs.post(Aura.app, Notifs.ID_SERVICE, buildNotification())
    }
  }
}

/** Redemarre le service au boot et apres une mise a jour de l'app, s'il etait actif. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    Aura.init(context)
    if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    if (Aura.prefs.getBoolean("service_enabled", false) && Aura.token.isNotEmpty()) {
      AuraService.start(context, false)
    }
  }
}
