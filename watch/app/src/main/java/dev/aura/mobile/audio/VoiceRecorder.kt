package dev.aura.mobile.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Enregistrement AAC dans un conteneur MP4 (`.m4a`), 16 kHz mono, 32 kbit/s (PROTOCOL.md section 3).
 * Arrêt automatique à 60 s : [onMaxDuration] est alors appelé (sur le thread principal).
 */
class VoiceRecorder(private val context: Context) {
  private var recorder: MediaRecorder? = null
  private var maxReached = false
  var file: File? = null
    private set

  val isRecording: Boolean get() = recorder != null

  fun start(id: String, onMaxDuration: () -> Unit): Boolean {
    stopQuietly()
    val dir = File(context.cacheDir, "voice").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val out = File(dir, "$id.m4a")
    maxReached = false
    val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
    return try {
      r.setAudioSource(MediaRecorder.AudioSource.MIC)
      r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
      r.setAudioSamplingRate(16_000)
      r.setAudioChannels(1)
      r.setAudioEncodingBitRate(32_000)
      r.setMaxDuration(MAX_DURATION_MS)
      r.setOutputFile(out.absolutePath)
      r.setOnInfoListener { _, what, _ ->
        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
          maxReached = true
          onMaxDuration()
        }
      }
      r.prepare()
      r.start()
      recorder = r
      file = out
      true
    } catch (e: Exception) {
      Log.e(TAG, "start", e)
      r.release()
      false
    }
  }

  /** Arrête et rend le fichier si l'enregistrement est exploitable. */
  fun stop(): File? {
    val r = recorder ?: return null
    recorder = null
    // Après MAX_DURATION_REACHED le fichier est déjà finalisé : une exception de stop() n'invalide rien.
    val ok = runCatching { r.stop() }.onFailure { Log.w(TAG, "stop", it) }.isSuccess || maxReached
    r.release()
    return file?.takeIf { ok && it.exists() && it.length() > 0 }
  }

  fun stopQuietly() {
    recorder?.let { r ->
      runCatching { r.stop() }
      r.release()
    }
    recorder = null
  }

  companion object {
    const val MAX_DURATION_MS = 60_000
    private const val TAG = "AuraRecorder"
  }
}
