package dev.aura.mobile.health

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Permissions santé : à partir d'Android 16 (API 36, Wear OS 6) BODY_SENSORS est remplacée par
 * les permissions granulaires `android.permission.health.*`. On gère les deux selon la version.
 */
object Perms {
  const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
  const val READ_HEALTH_DATA_IN_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

  private val isApi36 get() = Build.VERSION.SDK_INT >= 36

  val heartRate: String get() = if (isApi36) READ_HEART_RATE else Manifest.permission.BODY_SENSORS

  /** Lecture santé en arrière-plan : n'existe qu'à partir d'Android 13 (avant, la permission de premier plan suffit). */
  val heartRateBackground: String
    get() = if (isApi36) READ_HEALTH_DATA_IN_BACKGROUND else BODY_SENSORS_BACKGROUND

  const val ACTIVITY = Manifest.permission.ACTIVITY_RECOGNITION
  const val MIC = Manifest.permission.RECORD_AUDIO
  const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
  private const val BODY_SENSORS_BACKGROUND = "android.permission.BODY_SENSORS_BACKGROUND"

  /** Permissions qui n'existent pas sur les versions plus anciennes : implicitement accordées. */
  private fun introducedIn(permission: String): Int = when (permission) {
    NOTIFICATIONS, BODY_SENSORS_BACKGROUND -> Build.VERSION_CODES.TIRAMISU
    READ_HEART_RATE, READ_HEALTH_DATA_IN_BACKGROUND -> 36
    else -> 1
  }

  /** Noms courts des erreurs `permission:<nom>` renvoyées au téléphone. */
  object Codes {
    const val HEART_RATE = "heart_rate"
    const val HEART_RATE_BACKGROUND = "heart_rate_background"
    const val ACTIVITY = "activity_recognition"
    const val NOTIFICATIONS = "notifications"
  }

  fun granted(context: Context, permission: String): Boolean =
    Build.VERSION.SDK_INT < introducedIn(permission) ||
      ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
