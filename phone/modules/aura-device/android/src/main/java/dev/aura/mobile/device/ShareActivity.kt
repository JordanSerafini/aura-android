package dev.aura.mobile.device

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.random.Random

/**
 * « Partager vers Aura » (PROTOCOL.md §7.2 origin "share") : feuille legere par-dessus l'appli d'origine,
 * consigne pre-remplie, envoi `chat` dans une conversation NEUVE (un contenu partage merite son onglet ;
 * « Envoyer et ouvrir » y mene directement : aura://conv/<id> -> #conv=<id> de la PWA).
 *
 * Texte/lien -> `text` ; image -> `image` (JPEG 1280 px) ; PDF -> texte des 5 premieres pages
 * (PdfRenderer, Android 15+) + 1re page en `image` + le PDF lui-meme en `file` s'il fait moins de 4,5 Mo.
 */
class ShareActivity : Activity() {
  companion object {
    private const val TEXT_MAX = 15_000  // le bridge refuse au-dela de 20 000 caracteres
    private const val PDF_PAGES = 5
    private const val PDF_FILE_MAX = 4_500_000  // base64 < 6 Mo (protocol.MAX_IMAGE_B64)
  }

  private class Shared(
    val text: String?,
    val subject: String?,
    val images: List<Uri>,
    val pdfs: List<Uri>,
  )

  private val main = Handler(Looper.getMainLooper())
  private lateinit var shared: Shared
  private lateinit var consigne: EditText
  private lateinit var status: TextView
  private lateinit var buttons: LinearLayout
  private var sending = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Aura.init(this)
    shared = parse(intent)
    if (shared.text.isNullOrBlank() && shared.images.isEmpty() && shared.pdfs.isEmpty()) {
      finish()
      return
    }
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    setContentView(build())
    // service de premier plan : on est au premier plan, Android l'autorise
    if (!Aura.serviceRunning && Aura.token.isNotEmpty()) AuraService.start(this, true)
  }

  @Suppress("DEPRECATION")
  private fun parse(i: Intent): Shared {
    val streams = mutableListOf<Uri>()
    if (i.action == Intent.ACTION_SEND_MULTIPLE) {
      i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { streams += it }
    } else {
      (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { streams += it }
    }
    val images = mutableListOf<Uri>()
    val pdfs = mutableListOf<Uri>()
    for (u in streams) {
      val type = contentResolver.getType(u) ?: i.type ?: ""
      when {
        type.startsWith("image/") -> images += u
        type == "application/pdf" || u.toString().lowercase().endsWith(".pdf") -> pdfs += u
      }
    }
    val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
    return Shared(text, i.getStringExtra(Intent.EXTRA_SUBJECT), images, pdfs)
  }

  private fun defaultPrompt(): String = when {
    shared.pdfs.isNotEmpty() -> "Résume ce document"
    shared.images.isNotEmpty() -> "Qu'en penses-tu ?"
    shared.text?.let { it.startsWith("http://") || it.startsWith("https://") } == true -> "Résume cette page"
    else -> "Résume"
  }

  // ─── Vues ───────────────────────────────────────────────────────────────

  private fun build(): View {
    val dp = { v: Float -> NativeUi.dp(this, v) }
    val root = FrameLayout(this).apply {
      setBackgroundColor(Color.parseColor("#88000000"))
      setOnClickListener { if (!sending) finish() }
    }
    val sheet = NativeUi.column(this, 12f).apply {
      background = NativeUi.rounded(NativeUi.CARD, dp(22f).toFloat(), NativeUi.BORDER, dp(1f))
      setPadding(dp(18f), dp(18f), dp(18f), dp(20f))
      isClickable = true
    }
    sheet.addView(NativeUi.text(this, 18f, NativeUi.TEXT, true).apply { text = "Envoyer à Aura" })
    sheet.addView(preview())

    val chips = NativeUi.row(this, 8f)
    listOf("Résume", "Qu'en penses-tu ?", "Traduis en français", "Points clés", "Que dois-je faire ?").forEach { p ->
      chips.addView(NativeUi.button(this, p, "chip") { consigne.setText(p); consigne.setSelection(p.length) })
    }
    sheet.addView(HorizontalScrollView(this).apply {
      isHorizontalScrollBarEnabled = false
      addView(chips)
    })

    consigne = EditText(this).apply {
      setText(defaultPrompt())
      setSelection(text.length)
      setTextColor(NativeUi.TEXT)
      setHintTextColor(NativeUi.DIM)
      hint = "Consigne pour Aura"
      background = NativeUi.rounded(NativeUi.BG, dp(12f).toFloat(), NativeUi.BORDER, dp(1f))
      setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
      maxLines = 4
    }
    sheet.addView(consigne)

    status = NativeUi.text(this, 14f, NativeUi.DIM).apply { visibility = View.GONE }
    sheet.addView(status)

    buttons = NativeUi.row(this, 10f)
    buttons.addView(NativeUi.weight(NativeUi.button(this, "Envoyer", "ghost") { send(open = false) }))
    buttons.addView(NativeUi.weight(NativeUi.button(this, "Envoyer et ouvrir") { send(open = true) }))
    sheet.addView(buttons)

    val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
    lp.setMargins(dp(8f), 0, dp(8f), dp(8f))
    root.addView(sheet, lp)
    root.setOnApplyWindowInsetsListener { v, insets ->
      val b = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
      v.setPadding(0, b.top, 0, b.bottom)
      insets
    }
    return root
  }

  private fun preview(): View {
    val dp = { v: Float -> NativeUi.dp(this, v) }
    val box = NativeUi.row(this, 12f).apply {
      background = NativeUi.rounded(NativeUi.BG, dp(12f).toFloat())
      setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
    }
    val thumb = ImageView(this).apply {
      scaleType = ImageView.ScaleType.CENTER_CROP
      background = NativeUi.rounded(NativeUi.CARD, dp(8f).toFloat())
      clipToOutline = true
    }
    val label = NativeUi.text(this, 14f, NativeUi.TEXT).apply { maxLines = 4 }
    val first = shared.images.firstOrNull()
    val pdf = shared.pdfs.firstOrNull()
    when {
      first != null -> {
        box.addView(thumb, LinearLayout.LayoutParams(dp(64f), dp(64f)))
        Aura.pool.execute {
          val bmp = try { Images.fromUri(this, first, 60)?.let { android.graphics.BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size) } } catch (e: Exception) { null }
          main.post { if (bmp != null) thumb.setImageBitmap(bmp) }
        }
        label.text = if (shared.images.size > 1) "${shared.images.size} images (la 1re est jointe)" else "Image"
      }
      pdf != null -> {
        box.addView(thumb, LinearLayout.LayoutParams(dp(52f), dp(68f)))
        val name = displayName(pdf) ?: "document.pdf"
        label.text = "📄 $name"
        Aura.pool.execute {
          val page = try { renderFirstPage(pdf, 200) } catch (e: Exception) { null }
          main.post { if (page != null) thumb.setImageBitmap(page) }
        }
      }
      else -> label.text = shared.text.orEmpty().take(300)
    }
    if (first != null && !shared.text.isNullOrBlank()) label.append("\n" + shared.text!!.take(160))
    box.addView(NativeUi.weight(label))
    return box
  }

  // ─── Envoi ──────────────────────────────────────────────────────────────

  private fun setStatus(t: String, color: Int = NativeUi.DIM) = main.post {
    status.text = t
    status.setTextColor(color)
    status.visibility = View.VISIBLE
  }

  private fun send(open: Boolean) {
    if (sending) return
    sending = true
    buttons.visibility = View.GONE
    consigne.isEnabled = false
    setStatus("Préparation…")
    val prompt = consigne.text.toString().trim().ifEmpty { defaultPrompt() }
    Aura.pool.execute {
      val payload = try { buildPayload(prompt) } catch (e: Exception) {
        Log.w(Aura.TAG, "partage illisible", e)
        failed("Contenu illisible : ${e.message}")
        return@execute
      }
      setStatus("Envoi…")
      val id = payload.getString("id")
      Aura.relay.submit(Relay.Req(id, payload, ShareSink(open), newConv = true))
    }
  }

  private fun failed(msg: String) = main.post {
    sending = false
    status.text = msg
    status.setTextColor(NativeUi.BAD)
    status.visibility = View.VISIBLE
    buttons.visibility = View.VISIBLE
    consigne.isEnabled = true
  }

  private fun buildPayload(prompt: String): JSONObject {
    val id = "s" + Random.nextLong().toULong().toString(16).take(11)
    val p = JSONObject().put("type", "chat").put("id", id).put("origin", "share")
    val sb = StringBuilder(prompt)
    shared.subject?.takeIf { it.isNotBlank() && it != shared.text }?.let { sb.append("\n\n[Sujet : ").append(it.take(200)).append("]") }
    shared.text?.takeIf { it.isNotBlank() }?.let { sb.append("\n\n").append(it) }
    shared.images.firstOrNull()?.let { uri ->
      val jpeg = Images.fromUri(this, uri) ?: throw IllegalStateException("image illisible")
      p.put("image", jpeg.base64())
      if (shared.images.size > 1) sb.append("\n\n[${shared.images.size - 1} autre(s) image(s) partagée(s) non jointe(s)]")
    }
    shared.pdfs.firstOrNull()?.let { uri -> addPdf(uri, p, sb, hasImage = p.has("image")) }
    if (shared.pdfs.size > 1) sb.append("\n\n[${shared.pdfs.size - 1} autre(s) PDF non joint(s)]")
    p.put("text", if (sb.length > TEXT_MAX) sb.take(TEXT_MAX - 1).toString() + "…" else sb.toString())
    return p
  }

  private fun addPdf(uri: Uri, p: JSONObject, sb: StringBuilder, hasImage: Boolean) {
    val name = displayName(uri) ?: "document.pdf"
    contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
      PdfRenderer(fd).use { r ->
        val pages = r.pageCount
        val text = StringBuilder()
        if (Build.VERSION.SDK_INT >= 35) {
          for (i in 0 until minOf(PDF_PAGES, pages)) {
            r.openPage(i).use { page ->
              page.textContents.forEach { text.append(it.text).append('\n') }
            }
            if (text.length > TEXT_MAX) break
          }
        }
        if (!hasImage) {
          r.openPage(0).use { page -> p.put("image", Images.jpeg(renderPage(page, Images.MAX_SIDE), 80).base64()) }
        }
        val attach = (fileSize(uri) ?: Long.MAX_VALUE) <= PDF_FILE_MAX
        if (attach) {
          val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
          p.put("file", JSONObject().put("name", name).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)))
        }
        sb.append("\n\n[PDF « ").append(name).append(" », ").append(pages).append(" page").append(if (pages > 1) "s" else "")
        sb.append(if (!hasImage) " ; 1re page jointe en image" else "")
        sb.append(if (attach) " ; fichier complet joint" else " ; fichier trop lourd, non joint")
        if (text.isNotBlank()) {
          sb.append(" ; texte des ").append(minOf(PDF_PAGES, pages)).append(" premières pages ci-dessous]\n").append(text.toString().trim())
        } else {
          sb.append("]")
        }
      }
    }
  }

  private fun renderPage(page: PdfRenderer.Page, maxSide: Int): Bitmap {
    val scale = maxSide.toFloat() / maxOf(page.width, page.height)
    val w = (page.width * scale).toInt().coerceAtLeast(1)
    val h = (page.height * scale).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.eraseColor(Color.WHITE)  // un PDF est transparent par defaut : texte noir sur fond noir en JPEG
    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    return bmp
  }

  private fun renderFirstPage(uri: Uri, maxSide: Int): Bitmap? =
    contentResolver.openFileDescriptor(uri, "r")?.use { fd -> PdfRenderer(fd).use { r -> r.openPage(0).use { renderPage(it, maxSide) } } }

  private fun displayName(uri: Uri): String? = try {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
      if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment
  } catch (e: Exception) { uri.lastPathSegment }

  private fun fileSize(uri: Uri): Long? = try {
    contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
      if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
    }
  } catch (e: Exception) { null }

  /** Suivi de l'envoi : « Envoyé » des que le bridge a pris le message, puis fermeture (ou ouverture). */
  private inner class ShareSink(val open: Boolean) : RelaySink {
    @Volatile private var conv: String? = null
    @Volatile private var closed = false

    override fun conv(id: String) { conv = id }

    override fun state(state: String, text: String?) {
      when (state) {
        "thinking", "talking", "done" -> accepted()
        "error", "offline" -> if (!closed) failed(text ?: "Échec de l'envoi")
      }
    }

    override fun transcript(text: String) = accepted()

    private fun accepted() {
      if (closed) return
      closed = true
      setStatus("Envoyé ✓", NativeUi.OK)
      main.postDelayed({
        if (open) {
          val c = conv
          val i = Intent(Intent.ACTION_VIEW, Uri.parse(if (c != null) "aura://conv/$c" else "aura://open"))
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
          try { startActivity(i) } catch (e: Exception) { Notifs.launchAppIntent(this@ShareActivity)?.send() }
        }
        finish()
      }, if (open) 400L else 1100L)
    }
  }
}
