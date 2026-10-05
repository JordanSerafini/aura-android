package dev.aura.mobile.device

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** Etat des permissions et liste des actions reellement disponibles (device_caps). */
object Perms {
  private val ctx get() = Aura.app

  fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

  fun notifListener(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
  fun dnd(): Boolean = ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
  fun battery(): Boolean = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
  fun overlay(): Boolean = Settings.canDrawOverlays(ctx)
  fun notifications(): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
    (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
  fun location(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
  fun locationBackground(): Boolean = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
  fun hasTelephony(): Boolean = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
  /** Aura est-elle l'assistant numerique ? (role perdu a chaque reinstallation de l'APK) */
  fun assistant(): Boolean = try {
    val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
    rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) &&
      rm.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
  } catch (e: Exception) {
    false
  }
  fun hasFlash(): Boolean = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)

  fun status(): JSONObject = JSONObject()
    .put("sms_send", granted(Manifest.permission.SEND_SMS))
    .put("sms_read", granted(Manifest.permission.READ_SMS))
    .put("phone", granted(Manifest.permission.CALL_PHONE))
    .put("phone_state", granted(Manifest.permission.READ_PHONE_STATE))  // sans elle, la sonnerie n'est jamais detectee
    .put("call_log", granted(Manifest.permission.READ_CALL_LOG))
    .put("contacts", granted(Manifest.permission.READ_CONTACTS))
    .put("calendar_read", granted(Manifest.permission.READ_CALENDAR))
    .put("calendar_write", granted(Manifest.permission.WRITE_CALENDAR))
    .put("location", location())
    .put("location_background", locationBackground())
    .put("notifications", notifications())
    .put("microphone", granted(Manifest.permission.RECORD_AUDIO))
    .put("notification_listener", notifListener())
    .put("dnd_access", dnd())
    .put("battery_unrestricted", battery())
    .put("overlay", overlay())
    .put("promoted_notifications", LiveUpdate.canPromote())
    .put("assistant", assistant())
    .put("camera", granted(Manifest.permission.CAMERA))
    .put("accessibility", AuraAccessibilityService.enabled())
    .put("usage_access", AppUsage.granted())  // app_usage (03/10) : acces special « Acces a l'utilisation »

  /** Leve permission:<nom> (nom = cle de status()) si la permission manque. */
  fun require(name: String, ok: Boolean) {
    if (!ok) throw ActionError("permission:$name", "Permission manquante sur le téléphone : $name (onglet Actions de l'app Aura)")
  }

  fun requireRuntime(name: String, p: String) = require(name, granted(p))

  fun caps(): List<String> {
    val caps = mutableListOf<String>()
    val tel = hasTelephony()
    if (tel && granted(Manifest.permission.SEND_SMS)) caps += "sms_send"
    if (granted(Manifest.permission.READ_SMS)) caps += "sms_list"
    if (tel && granted(Manifest.permission.CALL_PHONE)) caps += "call"
    if (granted(Manifest.permission.READ_CALL_LOG)) caps += "call_log"
    if (granted(Manifest.permission.READ_CONTACTS)) caps += "contacts_search"
    if (notifListener()) caps += listOf("notif_list", "notif_reply", "notif_dismiss", "notif_open", "media_now")
    caps += "message_send"  // repli sans acces notifications : ouverture de la conversation pre-remplie
    if (granted(Manifest.permission.READ_CALENDAR)) caps += "calendar_list"
    if (granted(Manifest.permission.WRITE_CALENDAR)) caps += "calendar_add"
    caps += listOf("alarm_set", "timer_set")
    if (location()) caps += "location_get"
    caps += listOf("device_status", "volume_set", "ringer_mode")
    if (dnd()) caps += "dnd_set"
    if (hasFlash()) caps += "flashlight"
    caps += listOf("find_phone", "media_control", "open_app", "open_url", "navigate", "clipboard_set", "clipboard_get",
      "email_compose", "speak")
    // app_usage (03/10) : declaree MEME sans l'acces a l'utilisation, pour qu'Aura sache la demander ; elle repond alors
    // permission:usage_access avec le chemin exact (plutot qu'une action absente dont personne ne saurait pourquoi)
    caps += "app_usage"
    // §7.4 : declarees seulement quand leurs prerequis sont remplis (camera app fermee = « par-dessus »)
    if (CameraCapture.available() && overlay()) caps += "camera_snap"
    // ui_act (§9.2) : seulement quand le service d'accessibilite est LIE ; la liste blanche, elle, se verifie
    // a chaque demande (un paquet absent → app_not_allowed), pas ici
    if (AuraAccessibilityService.instance != null) caps += listOf("screen_read", "ui_act")
    if (Aura.watch.connected()) caps += listOf("watch_notify", "watch_vibrate", "watch_heart_rate", "watch_steps", "watch_battery")
    return caps
  }

  /** Ecrans systeme des permissions speciales. */
  fun settingsIntent(kind: String): Intent? {
    val pkg = ctx.packageName
    val pkgUri = Uri.parse("package:$pkg")
    return when (kind) {
      "app_info", "restricted" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
      "notification_listener" -> if (Build.VERSION.SDK_INT >= 30) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
          .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(ctx, AuraNotificationListener::class.java).flattenToString())
      } else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
      "notification_listener_list" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
      "dnd_access" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
      "battery_unrestricted" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
      "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
      "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
      // Assistant numerique : le role ASSISTANT ne se demande pas par RoleManager (non « requestable ») ;
      // cette page est « Applis par defaut → Assistant numerique » (One UI : « Appli d'assistance »)
      "assistant" -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
      "default_apps" -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
      // page du service Aura directement (Android 13+), sinon la liste Accessibilite
      "accessibility" -> if (Build.VERSION.SDK_INT >= 33) {
        Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")  // constante non publique du SDK
          .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(ctx, AuraAccessibilityService::class.java).flattenToString())
      } else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
      "accessibility_list" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
      // app_usage : la page d'Aura dans « Acces aux donnees d'utilisation » (Android 10+ accepte package:), sinon la liste
      "usage_access" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri)
      "usage_access_list" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
      // Live Updates (Android 16) : page « Notifications en direct » de l'appli
      "promoted_notifications" -> if (Build.VERSION.SDK_INT >= 36) {
        Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
      } else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
      else -> null
    }
  }

  fun openSettings(from: Context, kind: String): Boolean {
    val i = settingsIntent(kind) ?: return false
    if (from !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
      from.startActivity(i)
      true
    } catch (e: Exception) {
      // ecran absent (surcouche constructeur) : repli sur Infos de l'appli
      if (kind == "app_info") return false
      if (kind == "assistant") return openSettings(from, "default_apps")
      if (kind == "accessibility") return openSettings(from, "accessibility_list")
      if (kind == "usage_access") return openSettings(from, "usage_access_list")
      openSettings(from, "app_info")
    }
  }
}
