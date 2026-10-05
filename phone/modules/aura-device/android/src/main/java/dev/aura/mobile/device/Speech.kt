package dev.aura.mobile.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Reconnaissance vocale du systeme, sans passer par le reconnaisseur « par defaut ».
 *
 * Piege : choisir Aura comme assistant numerique fait aussi de SON RecognitionService le reconnaisseur
 * par defaut du telephone (Settings.Secure.voice_recognition_service). Un
 * SpeechRecognizer.createSpeechRecognizer(ctx) tomberait alors sur nous-memes. On vise donc
 * explicitement le reconnaisseur embarque (API 31+) ou celui d'une autre appli (Google).
 */
object Speech {
  private val PREFERRED = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts",
    "com.google.android.as", "com.samsung.android.bixby.agent")

  /** Autre service de reconnaissance installe (pas le notre), Google en priorite. */
  fun otherService(ctx: Context): ComponentName? {
    val list = ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
      .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
      .filter { it.packageName != ctx.packageName }
    return list.minByOrNull { c -> PREFERRED.indexOf(c.packageName).let { if (it < 0) 99 else it } }
  }

  /** Reconnaisseur utilisable, null s'il n'y en a aucun (repli : enregistrement + Whisper du bridge). */
  fun create(ctx: Context): SpeechRecognizer? {
    if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
      try { return SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) } catch (e: Exception) { Log.w(Aura.TAG, "STT embarque", e) }
    }
    val other = otherService(ctx) ?: return null
    return try { SpeechRecognizer.createSpeechRecognizer(ctx, other) } catch (e: Exception) { null }
  }

  fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
}

/**
 * RecognitionService exige par le XML voice-interaction-service pour que « Aura » soit proposable
 * comme assistant numerique. Il n'a pas de moteur a lui : il relaie vers le reconnaisseur Google (ou
 * autre) pour que la dictee des autres applis continue de marcher quand Aura est l'assistant.
 */
class AuraRecognitionService : RecognitionService() {
  private var inner: SpeechRecognizer? = null

  override fun onStartListening(intent: Intent, cb: Callback) {
    inner?.destroy()
    val other = Speech.otherService(this)
    if (other == null) {
      cb.error(SpeechRecognizer.ERROR_CLIENT)
      return
    }
    val r = try { SpeechRecognizer.createSpeechRecognizer(this, other) } catch (e: Exception) { null }
    if (r == null) {
      cb.error(SpeechRecognizer.ERROR_CLIENT)
      return
    }
    inner = r
    r.setRecognitionListener(object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) = cb.readyForSpeech(params ?: Bundle())
      override fun onBeginningOfSpeech() = cb.beginningOfSpeech()
      override fun onRmsChanged(rmsdB: Float) = cb.rmsChanged(rmsdB)
      override fun onBufferReceived(buffer: ByteArray?) { if (buffer != null) cb.bufferReceived(buffer) }
      override fun onEndOfSpeech() = cb.endOfSpeech()
      override fun onError(error: Int) = cb.error(error)
      override fun onResults(results: Bundle?) = cb.results(results ?: Bundle())
      override fun onPartialResults(partialResults: Bundle?) = cb.partialResults(partialResults ?: Bundle())
      override fun onEvent(eventType: Int, params: Bundle?) = Unit
    })
    r.startListening(intent)
  }

  override fun onStopListening(cb: Callback) {
    inner?.stopListening()
  }

  override fun onCancel(cb: Callback) {
    inner?.cancel()
  }

  override fun onDestroy() {
    inner?.destroy()
    inner = null
    super.onDestroy()
  }
}
