package dev.aura.mobile.device

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Temps non saisi en un geste (PROTOCOL.md §11.11, logique : TimeLogic.kt).
 *
 *  - `time_proposals` : notification « 3 temps à valider (95 min) » (comptes seulement : ni ticket ni client sur l'écran
 *    verrouillé) ; le bouton « Tout valider » ouvre l'écran de confirmation natif, il N'ENVOIE RIEN ;
 *  - l'écran de confirmation (TimeConfirmActivity) liste ce qui sera saisi et ses minutes ; seul son bouton envoie
 *    `time_confirm {ids}`, sur les ids qu'il affiche ;
 *  - `time_result` : notification de résultat, échecs avec leur raison, « Copier » pour coller le texte dans le système de tickets.
 */
object TimeProposals {
  private const val ID = 0x4300
  private const val TAG = "time"
  private const val TAG_RESULT = "time_result"
  private const val PREF_COUNT = "time_count"

  @Volatile private var items: List<TimeItem> = emptyList()
  private val listeners = CopyOnWriteArrayList<() -> Unit>()

  /** Propositions en attente telles que le bridge les a dites (mémoire : perdues au redémarrage du processus, `time_list` les redonne). */
  fun items(): List<TimeItem> = items

  /** Nombre de propositions en attente d'après le dernier message (sert la complication de la montre). */
  fun count(): Int = Aura.prefs.getInt(PREF_COUNT, 0)

  fun addListener(l: () -> Unit) { listeners += l }
  fun removeListener(l: () -> Unit) { listeners -= l }

  /** Thread Aura.io. `time_proposals` : à 17 h, après un `time_confirm` (ce qui reste), ou en réponse à `time_list`. */
  fun onProposals(msg: JSONObject) {
    val list = TimeLogic.parseProposals(msg)
    items = list
    val before = count()
    Aura.prefs.edit().putInt(PREF_COUNT, list.size).apply()
    val ctx = Aura.app
    if (list.isEmpty()) {
      NotificationManagerCompat.from(ctx).cancel(TAG, ID)
    } else if (Notifs.canPost(ctx)) {
      post(ctx, list)
    }
    if (before != list.size) WatchStatus.push()
    listeners.forEach { try { it() } catch (e: Exception) { Log.w(Aura.TAG, "ecran des temps", e) } }
    Log.i(Aura.TAG, "time_proposals : ${list.size}")  // jamais les titres ni les clients
  }

  @Suppress("MissingPermission")
  private fun post(ctx: Context, list: List<TimeItem>) {
    val open = confirmIntent(ctx, 0, fromAction = false)
    val public = NotificationCompat.Builder(ctx, Notifs.CH_TIME).setSmallIcon(R.drawable.ic_aura).setContentTitle("Temps à valider").build()
    val b = NotificationCompat.Builder(ctx, Notifs.CH_TIME)
      .setSmallIcon(R.drawable.ic_aura)
      .setContentTitle(TimeLogic.summary(list))
      .setContentText("Aura a chiffré le temps passé : vérifie avant de valider")
      .setCategory(NotificationCompat.CATEGORY_REMINDER)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(public)
      .setOnlyAlertOnce(true)  // la même liste qui se met à jour (après un time_confirm) ne refait pas de bruit
      .setAutoCancel(true)
      .setContentIntent(open)
      .addAction(0, "Tout valider", confirmIntent(ctx, 1, fromAction = true))
    try {
      NotificationManagerCompat.from(ctx).notify(TAG, ID, b.build())
    } catch (e: SecurityException) {
      // POST_NOTIFICATIONS retiree entre le test et l'appel
    }
  }

  private fun confirmIntent(ctx: Context, req: Int, fromAction: Boolean): PendingIntent {
    val i = Intent(ctx, TimeConfirmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      .putExtra(TimeConfirmActivity.EXTRA_FROM_ACTION, fromAction)
    return PendingIntent.getActivity(ctx, 0x4300 + req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }

  /** Thread Aura.io. `time_result` : réponse à NOTRE `time_confirm`. */
  @Suppress("MissingPermission")
  fun onResult(msg: JSONObject) {
    val r = TimeLogic.parseResult(msg) ?: return
    val ctx = Aura.app
    if (!Notifs.canPost(ctx)) return
    val copy = TimeLogic.copyBlock(r)
    val public = NotificationCompat.Builder(ctx, Notifs.CH_TIME).setSmallIcon(R.drawable.ic_aura).setContentTitle("Saisie des temps").build()
    val text = TimeLogic.resultText(r)
    val b = NotificationCompat.Builder(ctx, Notifs.CH_TIME)
      .setSmallIcon(R.drawable.ic_aura)
      .setContentTitle(TimeLogic.resultTitle(r))
      .setContentText(text.lineSequence().first())
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setCategory(NotificationCompat.CATEGORY_STATUS)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(public)
      .setAutoCancel(true)
      .setContentIntent(Notifs.launchAppIntent(ctx))
    if (copy != null) {
      b.addAction(0, "Copier", Notifs.receiverIntent(ctx, 0x4310, ActionReceiver.ACTION_COPY_TEXT, mapOf("text" to copy)))
    }
    try {
      NotificationManagerCompat.from(ctx).notify(TAG_RESULT, ID, b.build())
    } catch (e: SecurityException) {
      // idem
    }
    Log.i(Aura.TAG, "time_result : ${r.ok.size} ok, ${r.failed.size} echec(s)")
  }

  /** « Copier » : le texte à coller dans le système de tickets. L'écriture dans le presse-papiers est permise en arrière-plan (seule la lecture ne l'est pas). */
  fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Temps à saisir", text))
    Usage.count("notif.copier")
    Aura.main.post { Toast.makeText(ctx, "Copié", Toast.LENGTH_SHORT).show() }
  }
}

/**
 * Écran de confirmation natif : la liste de ce qui va être saisi (ticket, client, minutes, preuve) et le total. RIEN ne part
 * avant l'appui sur « Valider » ; l'écran n'est pas affiché par-dessus l'écran verrouillé (Android demande le déverrouillage
 * avant de l'ouvrir depuis la notification). Les ids envoyés sont exactement ceux qui sont affichés au moment de l'appui.
 */
class TimeConfirmActivity : Activity() {
  companion object {
    const val EXTRA_FROM_ACTION = "from_action"
  }

  private val main = Handler(Looper.getMainLooper())
  private lateinit var sheet: LinearLayout
  private var shown: List<TimeItem> = emptyList()
  private var sending = false
  private val refresh: () -> Unit = { main.post { render() } }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Aura.init(this)
    if (intent.getBooleanExtra(EXTRA_FROM_ACTION, false)) Usage.count("notif.tout_valider")
    val root = FrameLayout(this).apply {
      setBackgroundColor(Color.parseColor("#88000000"))
      setOnClickListener { if (!sending) finish() }
    }
    val dp = { v: Float -> NativeUi.dp(this, v) }
    sheet = NativeUi.column(this, 12f).apply {
      background = NativeUi.rounded(NativeUi.CARD, dp(22f).toFloat(), NativeUi.BORDER, dp(1f))
      setPadding(dp(18f), dp(18f), dp(18f), dp(20f))
      isClickable = true
    }
    val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
    lp.setMargins(dp(8f), 0, dp(8f), dp(8f))
    root.addView(sheet, lp)
    root.setOnApplyWindowInsetsListener { v, insets ->
      val b = insets.getInsets(android.view.WindowInsets.Type.systemBars())
      v.setPadding(0, b.top, 0, b.bottom)
      insets
    }
    setContentView(root)
    TimeProposals.addListener(refresh)
    render()
    // processus relancé depuis la notification : la liste n'est plus en mémoire, on la redemande
    if (TimeProposals.items().isEmpty()) Aura.io.execute { Aura.bridge.send(JSONObject().put("type", "time_list")) }
  }

  override fun onDestroy() {
    TimeProposals.removeListener(refresh)
    super.onDestroy()
  }

  private fun render(error: String? = null) {
    val dp = { v: Float -> NativeUi.dp(this, v) }
    shown = TimeProposals.items()
    sheet.removeAllViews()
    if (shown.isEmpty()) {
      sheet.addView(NativeUi.text(this, 18f, NativeUi.TEXT, true).apply { text = "Temps à valider" })
      sheet.addView(NativeUi.text(this, 15f, NativeUi.DIM).apply {
        text = if (Aura.bridge.online()) "Rien à valider pour l'instant (ou la liste arrive…)." else "Aura est hors ligne : la liste arrivera à la reconnexion."
      })
      sheet.addView(button("Fermer", "ghost") { finish() })
      return
    }
    sheet.addView(NativeUi.text(this, 18f, NativeUi.TEXT, true).apply { text = TimeLogic.summary(shown) })
    sheet.addView(NativeUi.text(this, 14f, NativeUi.DIM).apply {
      text = "Aura saisira ces temps dans le système de tickets (commentaire interne). Rien n'est envoyé sans ton accord."
    })
    val list = NativeUi.column(this, 10f)
    for (item in shown) {
      val row = NativeUi.column(this, 2f)
      row.addView(NativeUi.text(this, 15f, NativeUi.TEXT, true).apply { text = "#${item.ticket} · ${item.title.ifEmpty { "(sans titre)" }}" })
      row.addView(NativeUi.text(this, 14f, NativeUi.TEXT).apply { text = (if (item.client.isNotEmpty()) "${item.client} · " else "") + "${item.minutes} min" })
      if (item.evidence.isNotEmpty()) row.addView(NativeUi.text(this, 13f, NativeUi.DIM).apply { text = item.evidence })
      list.addView(row)
    }
    val scroll = ScrollView(this).apply { addView(list) }
    // la liste défile au-delà de la moitié de l'écran
    val width = resources.displayMetrics.widthPixels - dp(2 * 8f + 2 * 18f)
    list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
    val maxH = (resources.displayMetrics.heightPixels * 0.5f).toInt()
    sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minOf(list.measuredHeight, maxH)))
    sheet.addView(NativeUi.text(this, 15f, NativeUi.TEXT, true).apply { text = "Total : ${TimeLogic.total(shown)} min" })
    if (error != null) sheet.addView(NativeUi.text(this, 14f, NativeUi.BAD).apply { text = error })
    val buttons = NativeUi.row(this, 10f)
    buttons.addView(NativeUi.weight(button("Annuler", "ghost") { Usage.count("time.annule"); finish() }))
    buttons.addView(NativeUi.weight(button(if (shown.size == 1) "Valider ce temps" else "Valider ${shown.size} temps") { confirm() }))
    sheet.addView(buttons)
  }

  private fun button(label: String, kind: String = "primary", onClick: () -> Unit): TextView =
    NativeUi.button(this, label, kind, onClick).apply { minHeight = NativeUi.dp(this@TimeConfirmActivity, 48f) }  // cible >= 48 dp

  private fun confirm() {
    if (sending) return
    val ids = TimeLogic.confirmIds(shown)  // exactement ce qui est affiche
    if (ids.isEmpty()) return
    sending = true
    Aura.io.execute {
      val sent = Aura.bridge.send(TimeLogic.confirmMessage(ids))
      main.post {
        sending = false
        if (!sent) {
          render("Aura est hors ligne : rien n'a été envoyé. Réessaie dans un instant.")
        } else {
          Usage.count("time.confirme")
          Toast.makeText(this, "Envoyé à Aura : le résultat arrive en notification", Toast.LENGTH_LONG).show()
          finish()
        }
      }
    }
  }
}
