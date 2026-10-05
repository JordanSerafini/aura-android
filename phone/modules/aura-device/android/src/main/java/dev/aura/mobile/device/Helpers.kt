package dev.aura.mobile.device

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Ouverture d'activites depuis le service. Android 10+ bloque les lancements en arriere-plan, sauf si
 * l'app est au premier plan ou a « Afficher par-dessus les autres applis ». Sinon : notification
 * « Toucher pour ouvrir » (mode "notification").
 */
object Launcher {
  fun start(intent: Intent, label: String): String {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val pm = Aura.app.packageManager
    if (intent.resolveActivity(pm) == null) throw ActionError("unsupported", "Aucune appli installée pour : $label")
    if (Aura.appForeground || Settings.canDrawOverlays(Aura.app)) {
      try {
        Aura.app.startActivity(intent)
        return "opened"
      } catch (e: ActivityNotFoundException) {
        throw ActionError("unsupported", "Aucune appli installée pour : $label")
      } catch (e: SecurityException) {
        Log.w(Aura.TAG, "lancement refuse, repli notification", e)
      }
    }
    val id = Notifs.newId()
    val pi = PendingIntent.getActivity(Aura.app, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    Notifs.post(Aura.app, id, NotificationCompat.Builder(Aura.app, Notifs.CH_OPEN)
      .setSmallIcon(R.drawable.ic_aura)
      .setContentTitle("Aura : toucher pour ouvrir")
      .setContentText(label)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setAutoCancel(true)
      .setTimeoutAfter(5 * 60_000L)
      .setContentIntent(pi))
    return "notification"
  }

  /** PendingIntent d'une autre appli (contentIntent d'une notification, reponse). */
  fun sendPending(pi: PendingIntent, fillIn: Intent? = null) {
    val opts: Bundle? = if (Build.VERSION.SDK_INT >= 34) {
      ActivityOptions.makeBasic()
        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        .toBundle()
    } else null
    pi.send(Aura.app, 0, fillIn, null, null, null, opts)
  }
}

/** « Trouver mon telephone » : alarme au volume max 30 s, meme en silencieux, arretable depuis la notif. */
object FindPhone {
  private var player: MediaPlayer? = null
  private var previousVolume = -1
  private var stopTask: ScheduledFuture<*>? = null

  @Synchronized
  fun start(seconds: Int = 30) {
    stopLocked()
    val ctx = Aura.app
    val audio = ctx.getSystemService(AudioManager::class.java)
    previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
    audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
    val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
      ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
      ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    player = MediaPlayer().apply {
      setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build())
      setDataSource(ctx, uri)
      isLooping = true
      prepare()
      start()
    }
    vibrate(longArrayOf(0, 800, 400, 800, 400, 800))
    val stop = Notifs.receiverIntent(ctx, Notifs.ID_FIND, ActionReceiver.ACTION_FIND_STOP)
    Notifs.post(ctx, Notifs.ID_FIND, NotificationCompat.Builder(ctx, Notifs.CH_FIND)
      .setSmallIcon(R.drawable.ic_aura)
      .setContentTitle("Aura fait sonner ton téléphone")
      .setContentText("Touchez pour arrêter")
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setOngoing(true)
      .setContentIntent(stop)
      .addAction(0, "Arrêter", stop))
    stopTask = Aura.timer.schedule(Runnable { stop() }, seconds.toLong(), TimeUnit.SECONDS)
  }

  @Synchronized
  fun stop() = stopLocked()

  private fun stopLocked() {
    stopTask?.cancel(false)
    stopTask = null
    player?.let {
      try { it.stop() } catch (e: Exception) { /* deja arrete */ }
      it.release()
    }
    if (player != null && previousVolume >= 0) {
      Aura.app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
    }
    player = null
    Notifs.cancel(Aura.app, Notifs.ID_FIND)
  }

  fun vibrate(pattern: LongArray) {
    @Suppress("DEPRECATION")
    val v = Aura.app.getSystemService(Vibrator::class.java) ?: return
    v.vibrate(VibrationEffect.createWaveform(pattern, -1))
  }
}

/** Synthese vocale Android locale, fr-FR. */
object Speaker {
  private var tts: TextToSpeech? = null
  private var ready = false

  @Synchronized
  private fun engine(): TextToSpeech {
    tts?.let { if (ready) return it }
    val latch = CountDownLatch(1)
    var status = TextToSpeech.ERROR
    val t = TextToSpeech(Aura.app) { s -> status = s; latch.countDown() }
    if (!latch.await(10, TimeUnit.SECONDS) || status != TextToSpeech.SUCCESS) {
      t.shutdown()
      throw ActionError("unsupported", "Synthèse vocale du téléphone indisponible")
    }
    t.language = Locale.FRANCE
    tts = t
    ready = true
    return t
  }

  /** Bloque jusqu'a la fin de la lecture (120 s max). */
  fun speak(text: String) {
    val t = engine()
    val id = UUID.randomUUID().toString()
    val done = CountDownLatch(1)
    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) = Unit
      override fun onDone(utteranceId: String?) { if (utteranceId == id) done.countDown() }
      @Deprecated("API 21")
      override fun onError(utteranceId: String?) { if (utteranceId == id) done.countDown() }
    })
    val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
    if (t.speak(text, TextToSpeech.QUEUE_ADD, params, id) != TextToSpeech.SUCCESS) {
      throw ActionError("tts_failed", "La synthèse vocale a refusé le texte")
    }
    done.await(120, TimeUnit.SECONDS)
  }
}
