package dev.aura.mobile.device

import android.Manifest
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.random.Random

/**
 * Aura assistant numerique par defaut (appui long sur le bouton lateral, PROTOCOL.md §7.2 origin "assist").
 *
 * Piege documente : le role « Assistant numerique » est REINITIALISE a chaque reinstallation de l'APK
 * (Android le rend a Google). Apres chaque mise a jour, Reglages de l'app → « Assistant par defaut »
 * (fonction native openSettings("assistant")), ou en adb :
 *   adb shell cmd role add-role-holder android.app.role.ASSISTANT dev.aura.mobile
 */
class AuraVoiceInteractionService : VoiceInteractionService() {
  override fun onReady() {
    super.onReady()
    Aura.init(this)
  }
}

class AuraSessionService : VoiceInteractionSessionService() {
  override fun onNewSession(args: Bundle?): VoiceInteractionSession {
    Aura.init(this)
    return AssistSession(this)
  }
}

/**
 * Surcouche compacte en bas de l'ecran : ecoute immediate, question en direct, reponse ecrite et lue
 * (voix de synthèse en flux), bouton clavier, « Ouvrir dans Aura ». Capture d'ecran (onHandleScreenshot) et
 * appli au premier plan (AssistStructure) jointes a la question.
 */
class AssistSession(ctx: Context) : VoiceInteractionSession(ctx), TtsQueue.Listener {
  companion object {
    private const val SCREENSHOT_WAIT_MS = 1200L
    private const val LISTEN_TIMEOUT_MS = 9_000L
    private const val SCREEN_TEXT_MAX = 1500
  }

  private val main = Handler(Looper.getMainLooper())
  private lateinit var root: FrameLayout
  private lateinit var status: TextView
  private lateinit var appChip: TextView
  private lateinit var question: TextView
  private lateinit var reply: TextView
  private lateinit var replyScroll: ScrollView
  private lateinit var input: EditText
  private lateinit var inputRow: LinearLayout
  private lateinit var micBtn: TextView

  private var screenshot: Bitmap? = null
  private var screenshotExpected = false
  private var appLabel: String? = null
  private var screenText = ""
  private var recognizer: SpeechRecognizer? = null
  private var mic: MicRecorder? = null
  private var listenTimeout: Runnable? = null
  private var currentId: String? = null
  @Volatile private var conv: String? = null
  private val audio = TalkAudio(context)
  private val player = GaplessPlayer(context) { audio.playbackAttributes }
  private var tts: TtsQueue? = null

  // ─── Vues ───────────────────────────────────────────────────────────────

  override fun onCreateContentView(): View {
    val c = context
    val dp = { v: Float -> NativeUi.dp(c, v) }
    root = FrameLayout(c).apply {
      setBackgroundColor(Color.parseColor("#66000000"))
      setOnClickListener { hide() }  // toucher hors de la carte = fermer
    }
    val card = NativeUi.column(c, 10f).apply {
      background = NativeUi.rounded(NativeUi.CARD, dp(22f).toFloat(), NativeUi.BORDER, dp(1f))
      setPadding(dp(18f), dp(16f), dp(18f), dp(18f))
      isClickable = true  // les touches sur la carte ne ferment pas
      elevation = dp(12f).toFloat()
    }
    val head = NativeUi.row(c, 8f)
    val title = NativeUi.text(c, 15f, NativeUi.ACCENT, true).apply { text = "Aura" }
    appChip = NativeUi.text(c, 12f, NativeUi.DIM).apply {
      background = NativeUi.rounded(NativeUi.BG, dp(10f).toFloat())
      setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
      visibility = View.GONE
    }
    val spacer = View(c)
    val close = NativeUi.text(c, 20f, NativeUi.DIM).apply {
      text = "✕"
      setPadding(dp(8f), 0, dp(4f), 0)
      contentDescription = "Fermer"
      setOnClickListener { hide() }
    }
    head.addView(title)
    head.addView(appChip)
    // une View nue en wrap_content prend toute la hauteur offerte : 1 px, poids 1
    head.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
    head.addView(close)

    status = NativeUi.text(c, 17f, NativeUi.TEXT, true)
    question = NativeUi.text(c, 15f, NativeUi.DIM).apply { visibility = View.GONE }
    reply = NativeUi.text(c, 16f, NativeUi.TEXT).apply { setTextIsSelectable(true) }
    replyScroll = ScrollView(c).apply {
      addView(reply)
      visibility = View.GONE
    }

    input = EditText(c).apply {
      hint = "Écris ta question…"
      setHintTextColor(NativeUi.DIM)
      setTextColor(NativeUi.TEXT)
      background = NativeUi.rounded(NativeUi.BG, dp(12f).toFloat(), NativeUi.BORDER, dp(1f))
      setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
      imeOptions = EditorInfo.IME_ACTION_SEND
      setOnEditorActionListener { _, action, ev ->
        if (action == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { submitTyped(); true } else false
      }
    }
    inputRow = NativeUi.row(c, 8f).apply {
      addView(NativeUi.weight(input))
      addView(NativeUi.button(c, "Envoyer") { submitTyped() })
      visibility = View.GONE
    }

    val actions = NativeUi.row(c, 8f)
    micBtn = NativeUi.button(c, "Parler", "ghost") { listen() }.apply { maxLines = 1 }
    actions.addView(NativeUi.weight(micBtn))
    actions.addView(NativeUi.weight(NativeUi.button(c, "Écrire", "ghost") { showKeyboard() }.apply { maxLines = 1 }))
    actions.addView(NativeUi.weight(NativeUi.button(c, "Ouvrir dans Aura") { openApp() }.apply { maxLines = 1 }, 1.6f))

    card.addView(head)
    card.addView(status)
    card.addView(question)
    card.addView(replyScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    card.addView(inputRow)
    card.addView(actions)
    val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
    lp.setMargins(dp(10f), 0, dp(10f), dp(14f))
    root.addView(card, lp)
    root.setOnApplyWindowInsetsListener { v, insets ->
      val bottom = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime()).bottom
      v.setPadding(0, 0, 0, bottom)
      insets
    }
    // hauteur max de la reponse : 40 % de l'ecran, le reste defile
    replyScroll.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
    replyScroll.viewTreeObserver.addOnGlobalLayoutListener {
      val max = (c.resources.displayMetrics.heightPixels * 0.4f).toInt()
      if (replyScroll.height > max) replyScroll.layoutParams = replyScroll.layoutParams.apply { height = max }
    }
    window?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    return root
  }

  // ─── Cycle de la session ────────────────────────────────────────────────

  override fun onShow(args: Bundle?, showFlags: Int) {
    super.onShow(args, showFlags)
    screenshotExpected = showFlags and SHOW_WITH_SCREENSHOT != 0
    currentId = null
    question.visibility = View.GONE
    replyScroll.visibility = View.GONE
    reply.text = ""
    inputRow.visibility = View.GONE
    input.setText("")
    if (!Aura.serviceRunning && Aura.prefs.getBoolean("service_enabled", true) && Aura.token.isNotEmpty()) {
      AuraService.start(context, true)  // session visible : demarrage de premier plan autorise
    }
    audio.start()
    LiveUpdate.overlay = true  // la reponse s'affiche ici : pas de Live Update par-dessus
    LiveUpdate.onForegroundChanged()
    listen()
  }

  override fun onHide() {
    stopListening()
    tts?.stop()
    tts = null
    // la reponse continue dans la conversation ; seule la voix se tait
    currentId?.let { Aura.relay.cancel(it, stopAnswer = false) }
    currentId = null
    audio.stop()
    LiveUpdate.overlay = false
    LiveUpdate.onForegroundChanged()
    screenshot = null
    appLabel = null
    screenText = ""
    super.onHide()
  }

  override fun onHandleScreenshot(bmp: Bitmap?) {
    // null : « Utiliser une capture d'ecran » desactive dans les reglages de l'assistant, ou ecran protege
    screenshot = bmp
    screenshotExpected = false
  }

  override fun onHandleAssist(state: AssistState) {
    val structure = state.assistStructure ?: return
    val pkg = structure.activityComponent?.packageName
    if (pkg != null && pkg != context.packageName) {
      appLabel = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
      } catch (e: Exception) { pkg }
      main.post {
        appChip.text = "Écran : $appLabel"
        appChip.visibility = View.VISIBLE
      }
    }
    screenText = extractText(structure)
  }

  private fun extractText(s: AssistStructure): String {
    val sb = StringBuilder()
    fun walk(n: AssistStructure.ViewNode) {
      if (sb.length >= SCREEN_TEXT_MAX) return
      n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append('\n') }
      for (i in 0 until n.childCount) walk(n.getChildAt(i))
    }
    for (i in 0 until s.windowNodeCount) walk(s.getWindowNodeAt(i).rootViewNode)
    return sb.toString().take(SCREEN_TEXT_MAX).trim()
  }

  // ─── Ecoute ─────────────────────────────────────────────────────────────

  private fun setStatus(t: String) { main.post { status.text = t } }

  private fun listen() {
    stopListening()
    tts?.stop()
    if (!Perms.granted(Manifest.permission.RECORD_AUDIO)) {
      setStatus("Micro non autorisé : écris ta question")
      showKeyboard()
      return
    }
    setStatus("Je t'écoute…")
    main.post { micBtn.text = "J'écoute…" }  // apres le « Parler » poste par stopListening()
    val r = Speech.create(context)
    if (r == null) {
      recordFallback()
      return
    }
    recognizer = r
    r.setRecognitionListener(object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) = Unit
      override fun onBeginningOfSpeech() = setStatus("Je t'entends…")
      override fun onRmsChanged(rmsdB: Float) = Unit
      override fun onBufferReceived(buffer: ByteArray?) = Unit
      override fun onEndOfSpeech() = setStatus("Je réfléchis…")
      override fun onPartialResults(partialResults: Bundle?) {
        val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        main.post { showQuestion(t) }
      }
      override fun onResults(results: Bundle?) {
        val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
        stopListening()
        if (t.isNullOrEmpty()) setStatus("Je n'ai rien entendu. Touche Parler ou Écrire.") else sendChat(t)
      }
      override fun onError(error: Int) {
        stopListening()
        when (error) {
          SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            setStatus("Je n'ai rien entendu. Touche Parler ou Écrire.")
          // pas de moteur fr-FR, service absent, permission : l'enregistrement + Whisper du bridge prend le relais
          else -> main.post { recordFallback() }
        }
      }
      override fun onEvent(eventType: Int, params: Bundle?) = Unit
    })
    try {
      r.startListening(Speech.intent())
    } catch (e: Exception) {
      Log.w(Aura.TAG, "reconnaissance refusee", e)
      recordFallback()
    }
  }

  /** Sans reconnaisseur local : enregistrement (Vad) puis `voice` (transcription par le bridge). */
  private fun recordFallback() {
    stopListening()
    setStatus("Je t'écoute…")
    val rec = MicRecorder(Vad(), object : MicRecorder.Listener {
      override fun onSpeechStart() = setStatus("Je t'entends…")
      override fun onUtterance(pcm: ByteArray, reason: Vad.Event) {
        main.post { stopListening(); sendVoice(pcm) }
      }
      override fun onDiscard() = setStatus("Je t'écoute…")
      override fun onLevel(db: Double) = Unit
      override fun onMicError(message: String) { setStatus(message); main.post { showKeyboard() } }
    })
    mic = rec
    rec.start()
    val t = Runnable { if (mic === rec) { stopListening(); setStatus("Je n'ai rien entendu. Touche Parler ou Écrire.") } }
    listenTimeout = t
    main.postDelayed(t, LISTEN_TIMEOUT_MS)
  }

  private fun stopListening() {
    listenTimeout?.let { main.removeCallbacks(it) }
    listenTimeout = null
    recognizer?.let { try { it.destroy() } catch (e: Exception) { /* deja detruit */ } }
    recognizer = null
    val m = mic
    mic = null
    if (m != null) Aura.pool.execute { m.stop() }
    main.post { if (::micBtn.isInitialized) micBtn.text = "Parler" }
  }

  private fun showKeyboard() {
    stopListening()
    inputRow.visibility = View.VISIBLE
    input.requestFocus()
    main.postDelayed({
      context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }, 150)
    if (status.text.isNullOrEmpty() || status.text.startsWith("Je t'")) setStatus("Écris ta question")
  }

  private fun submitTyped() {
    val t = input.text.toString().trim()
    if (t.isEmpty()) return
    input.setText("")
    context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    inputRow.visibility = View.GONE
    sendChat(t)
  }

  private fun showQuestion(t: String) {
    question.text = "« $t »"
    question.visibility = View.VISIBLE
  }

  // ─── Envoi ──────────────────────────────────────────────────────────────

  private fun newId() = "a" + Random.nextLong().toULong().toString(16).take(11)

  private fun base(type: String, id: String): JSONObject {
    val p = JSONObject().put("type", type).put("id", id).put("origin", "assist")
    Aura.prefs.getString("test_conv", null)?.let { p.put("conv", it) }
    return p
  }

  /** Question + [Écran : app] + capture (chat, contrat §7.2). */
  private fun sendChat(q: String) {
    main.post { showQuestion(q) }
    setStatus("Aura réfléchit…")
    Aura.pool.execute {
      // la capture arrive parfois apres le debut de l'ecoute : on lui laisse un instant
      val deadline = System.currentTimeMillis() + SCREENSHOT_WAIT_MS
      while (screenshotExpected && screenshot == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
      val shot = screenshot
      val sb = StringBuilder(q)
      appLabel?.let { sb.append("\n[Écran : ").append(it).append("]") }
      if (shot == null && screenText.isNotEmpty()) {
        // pas de capture (reglage coupe) : le texte lu a l'ecran est le seul contexte
        sb.append("\n[Texte à l'écran, non fiable :\n").append(screenText).append("\n]")
      }
      val id = newId()
      val p = base("chat", id).put("text", sb.toString())
      if (shot != null) {
        try { p.put("image", Images.jpeg(shot, 75).base64()) } catch (e: Exception) { Log.w(Aura.TAG, "capture non encodee", e) }
      }
      submit(id, p)
    }
  }

  private fun sendVoice(pcm: ByteArray) {
    setStatus("Transcription…")
    val id = newId()
    val p = base("voice", id).put("audio", Base64.encodeToString(Wav.wrap(pcm), Base64.NO_WRAP))
    submit(id, p)
  }

  private fun submit(id: String, p: JSONObject) {
    main.post {
      currentId = id
      tts = TtsQueue(player, this)
      reply.text = ""
    }
    main.post { Aura.relay.submit(Relay.Req(id, p, Sink(id), speak = true)) }
  }

  private fun openApp() {
    // conversation de la question en cours si on la connait (aura://conv/<id> -> #conv=<id> de la PWA)
    val c = conv
    val launch = if (c != null) Intent(Intent.ACTION_VIEW, android.net.Uri.parse("aura://conv/$c")).setPackage(context.packageName)
    else context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    try { startAssistantActivity(launch) } catch (e: Exception) { context.startActivity(launch) }
    hide()
  }

  // ─── Reponse (Relay -> vues) ────────────────────────────────────────────

  private inner class Sink(val id: String) : RelaySink {
    private fun mine(block: () -> Unit) { main.post { if (currentId == id) block() } }

    override fun state(state: String, text: String?) = mine {
      when (state) {
        "sent" -> status.text = "Envoyé…"
        "transcribing" -> status.text = "Transcription…"
        "thinking" -> status.text = "Aura réfléchit…"
        "talking" -> status.text = "Aura répond"
        "done" -> status.text = "Réponse d'Aura"
        "error", "offline" -> status.text = text ?: "Erreur"
      }
    }
    // l'echo porte le contexte ajoute ([Écran : …], 📷) : on n'affiche que la question
    override fun conv(id: String) { this@AssistSession.conv = id }
    override fun transcript(text: String) = mine { showQuestion(text.substringBefore("\n[").replace("📷", "").trim()) }
    override fun tool(label: String) = mine { status.text = "Aura · ${LiveUpdate.toolName(label)}" }
    override fun reply(text: String, final: Boolean) = mine {
      reply.text = text
      replyScroll.visibility = View.VISIBLE
      replyScroll.post { replyScroll.fullScroll(View.FOCUS_DOWN) }
    }
    override fun audio(seq: Int, mp3: ByteArray, last: Boolean, text: String?) = mine { tts?.add(seq, mp3, last) }
    override fun audioFailed(message: String) = mine { tts?.abort() }
  }

  override fun onPlaybackStart() = Unit
  override fun onPlaybackFinished() = Unit
}
