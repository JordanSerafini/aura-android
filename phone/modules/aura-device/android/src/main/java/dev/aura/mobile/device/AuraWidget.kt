package dev.aura.mobile.device

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import java.time.ZoneId

/**
 * Widget « À traiter · Notifs · micro » (17/10 soir, idee 4 de la revue du 17/10 + veille : « widget À traiter », la
 * Badging API n'existant pas sur Android). RemoteViews classiques plutot que Glance : aucune dependance (Compose) de plus
 * pour trois nombres et trois boutons. Les nombres viennent de l'app (setCounts, pastilles de la PWA) ; le systeme rappelle
 * onUpdate toutes les 30 min, ce qui ne refait que le libelle « à 21:40 » (aucun reseau, aucun reveil de l'app).
 * Appuis : aura://view/actions, aura://notifs, aura://talk?autostart=1. Ce lien ne porte pas le jeton a usage unique de
 * la tuile (TalkNonce) : l'ecran Talk s'ouvre et l'utilisateur touche le micro, le garde-fou de l'autostart reste entier.
 */
class AuraWidget : AppWidgetProvider() {
  override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
    if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
  }

  companion object {
    private const val PREFS = "aura_widget"

    fun setCounts(context: Context, actions: Int, notifs: Int) {
      val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      val now = System.currentTimeMillis()
      // meme etat recu il y a moins d'une minute : rien a redessiner (la PWA renvoie ses pastilles a chaque rendu)
      if (p.getInt("actions", -1) == actions && p.getInt("notifs", -1) == notifs && now - p.getLong("at", 0L) < 60_000L) return
      p.edit().putInt("actions", actions).putInt("notifs", notifs).putLong("at", now).apply()
      refresh(context)
    }

    fun refresh(context: Context) {
      val manager = AppWidgetManager.getInstance(context) ?: return
      val ids = manager.getAppWidgetIds(ComponentName(context, AuraWidget::class.java))
      if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
    }

    private fun open(context: Context, code: Int, uri: String): PendingIntent {
      val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setClassName(context, "dev.aura.mobile.MainActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun views(context: Context): RemoteViews {
      val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      val t = WidgetLogic.texts(p.getInt("actions", 0), p.getInt("notifs", 0), p.getLong("at", 0L), System.currentTimeMillis(),
        ZoneId.systemDefault())
      return RemoteViews(context.packageName, R.layout.aura_widget).apply {
        setTextViewText(R.id.aura_widget_actions_n, t.actions)
        setTextViewText(R.id.aura_widget_notifs_n, t.notifs)
        setTextViewText(R.id.aura_widget_at, t.at)
        setContentDescription(R.id.aura_widget_actions, t.actionsA11y)
        setContentDescription(R.id.aura_widget_notifs, t.notifsA11y)
        setOnClickPendingIntent(R.id.aura_widget_actions, open(context, 41, "aura://view/actions"))
        setOnClickPendingIntent(R.id.aura_widget_notifs, open(context, 42, "aura://notifs"))
        setOnClickPendingIntent(R.id.aura_widget_mic, open(context, 43, "aura://talk?autostart=1"))
      }
    }
  }
}
