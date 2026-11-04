package dev.aura.mobile.device

import android.content.Context
import android.os.Build
import android.util.Log
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pont JS <-> natif. Tout le travail est dans le singleton Aura (vit sans le JS) ; ici on ne fait
 * que l'exposer. Les donnees passent en JSON texte : pas de conversion de types fragile.
 */
class AuraDeviceModule : Module() {
  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  override fun definition() = ModuleDefinition {
    Name("AuraDevice")

    Events("onState", "onLog", "onLogClear", "onConfirms", "onTalk", "onPause")

    OnCreate {
      Aura.init(context)
      Aura.appForeground = true  // le module nait quand le JS de l'activite demarre
      Aura.listener = { event, json -> sendEvent(event, mapOf("json" to json)) }
      // demarrage a froid de l'app (bundle JS charge), distinct des retours au premier plan (`ecran.aura`) ; et des prefs
      // neuves (installation propre, donnees effacees) : le 08/10, une reinstallation a efface les compteurs du jour
      // sans que rien ne le dise (UsageLogic.coldStartCounters)
      UsageLogic.coldStartCounters(Aura.prefs.contains(UsageLogic.PREF_SINCE), Aura.prefs.contains("asked_basics")).forEach { Usage.count(it) }
      if (!Aura.prefs.contains(UsageLogic.PREF_SINCE)) Aura.prefs.edit().putString(UsageLogic.PREF_SINCE, java.time.LocalDate.now().toString()).apply()
    }

    OnDestroy {
      Aura.listener = null
      Aura.talk?.stop()  // plus d'ecran pour la montrer : la session ne doit pas ecouter dans le vide
      Aura.talk = null
    }

    OnActivityEntersForeground {
      Aura.appForeground = true
      LiveUpdate.onForegroundChanged()
      if (Aura.serviceRunning) {
        Aura.bridge.kick(foreground = true)
        Aura.bridge.capsChanged()  // une permission a pu changer dans les reglages systeme
      }
    }

    OnActivityEntersBackground {
      Aura.appForeground = false
      LiveUpdate.onForegroundChanged()
      Usage.maybeReport()  // usage_report : au plus une fois par tranche de 6 h, des nombres seulement (PROTOCOL.md §11.18, §19.6)
    }

    // Rend "" si tout est pris en compte, sinon le motif du refus (jamais l'URL) : en release une URL qui n'est pas
    // wss:// n'est pas enregistree (BridgeUrl), le jeton ne partirait pas en clair
    Function("configure") { url: String, token: String, poste: String, version: String ->
      val e = Aura.prefs.edit()
      var refused = ""
      if (url.isNotBlank()) {
        if (BridgeUrl.accepted(url, Aura.debuggable)) e.putString("url", url.trim())
        else {
          refused = "URL du bridge refusée : wss:// obligatoire"
          Log.w(Aura.TAG, "configure : URL du bridge refusee (schema non wss://)")
        }
      }
      if (token.isNotBlank()) e.putString("token", token)
      if (poste.isNotBlank()) e.putString("poste", poste)
      e.putString("version", version).apply()
      refused
    }

    Function("startService") {
      AuraService.start(context, true)
    }

    Function("stopService") {
      AuraService.stop(context)
    }

    Function("getState") { Aura.stateJson() }

    // ─── Mode Talk (TalkScreen) ───────────────────────────────────────────
    // "" si la session demarre, sinon le motif (affiche tel quel)
    Function("talkStart") {
      if (!Perms.granted(android.Manifest.permission.RECORD_AUDIO)) return@Function "Micro non autorisé"
      if (Aura.token.isEmpty()) return@Function "Aucun jeton appareil"
      if (!Aura.serviceRunning) AuraService.start(context, true)
      Aura.talk?.stop()
      val s = TalkSession(emit = { json -> Aura.emit("onTalk", json.toString()) })
      Aura.talk = s
      s.start()
      keepScreenOn(true)
      ""
    }

    Function("talkTap") { Aura.talk?.tap() }
    // « Micro coupe pendant qu'Aura parle » (1.5.0) : pref persistante, appliquee a la session en cours s'il y en a une
    Function("talkSetMute") { on: Boolean ->
      Aura.prefs.edit().putString(BargeInLogic.PREF_MUTE, if (on) "1" else "0").apply()
      Aura.talk?.setMute(on)
    }

    Function("talkStop") {
      Aura.talk?.stop()
      Aura.talk = null
      keepScreenOn(false)
    }

    Function("talkState") { Aura.talk?.snapshot()?.toString() ?: "{\"state\":\"idle\"}" }

    // ecran visible cote JS (onglet ou « Talk ») : pas de Live Update par-dessus la reponse deja affichee
    Function("setScreen") { name: String -> LiveUpdate.setScreen(name) }

    // demande envoyee par la PWA de l'onglet Aura (id du chat/voice) : suivie par le Live Update
    Function("trackRequest") { id: String -> if (id.isNotBlank() && id.length <= 64) LiveUpdate.track(id) }
    // widget d'ecran d'accueil (17/10 soir) : les pastilles de la PWA, des qu'elle les envoie (AuraWidget.kt)
    Function("setWidgetCounts") { actions: Int, notifs: Int ->
      AuraWidget.setCounts(context, actions.coerceIn(0, 999), notifs.coerceIn(0, 999))
    }
    // haptique semantique de la PWA (17/10 soir, __auraNativeV 3) : effet systeme, suit le reglage « retour haptique »
    Function("haptic") { kind: String ->
      val c = HapticLogic.constant(kind, Build.VERSION.SDK_INT) ?: return@Function false
      val act = appContext.currentActivity ?: return@Function false
      act.runOnUiThread { act.window?.decorView?.performHapticFeedback(c) }
      true
    }

    Function("canPostPromoted") { LiveUpdate.canPromote() }

    // gestes du PC portes au S22 (21/10, __auraNativeV 5, PROTOCOL.md §32) : copie et partage du texte nettoye par le
    // bridge ; « Ce que j'ai copié » = l'AGE d'un texte copie ailleurs (ClipLogic), le contenu seulement sur demande
    Function("copyText") { text: String -> copyText(text) }
    Function("shareText") { text: String, subject: String -> shareText(text, subject) }
    Function("clipHint") { clipHint() }
    Function("clipText") { clipText() }
    // HUD hors de l'ecran d'accueil (1.9.0, PROTOCOL.md §35) : notification, Live Update, tuile, montre
    Function("getHud") { HudSurfaces.settingsJson(context).toString() }
    Function("setHud") { json: String -> HudSurfaces.setSettings(context, json) }
    Function("hudPinWidget") { HudSurfaces.pinWidget(appContext.currentActivity ?: context) }
    Function("hudAddTile") { HudSurfaces.addTile(appContext.currentActivity) }

    // barres systeme assorties au theme (1.4.0) : icones sombres sur fond clair et inversement ; le fond de la fenetre
    // (visible sous la barre de navigation transparente, edge-to-edge) prend celui des ecrans
    Function("setSystemBars") { light: Boolean, background: String -> systemBars(light, background) }

    // compteur d'usage (UsageLogic.kt) : un nom `[a-z0-9_.]{1,40}`, un nombre ; jamais un contenu
    Function("countUsage") { name: String -> Usage.count(name) }

    // ─── Contexte du telephone (§7.3) ─────────────────────────────────────
    Function("getPhoneContext") { try { PhoneContext.snapshot().toString() } catch (e: Exception) { "{}" } }
    Function("getZones") { PhoneContext.zones().toString() }
    // « Definir ma position actuelle comme Maison/Travail » : {ok, result|error, message}
    AsyncFunction("setZoneHere") { name: String, promise: Promise ->
      Aura.pool.execute {
        val res = JSONObject()
        try {
          res.put("ok", true).put("result", PhoneContext.setZoneHere(name))
        } catch (e: ActionError) {
          res.put("ok", false).put("error", e.code).put("message", e.message)
        } catch (e: Exception) {
          res.put("ok", false).put("error", "failed").put("message", e.message ?: e.javaClass.simpleName)
        }
        promise.resolve(res.toString())
      }
    }
    // « Effacer la zone » (17/10 soir) : {ok, result|error, message}, meme forme que setZoneHere
    AsyncFunction("removeZone") { name: String, promise: Promise ->
      Aura.pool.execute {
        val res = JSONObject()
        try {
          res.put("ok", true).put("result", PhoneContext.removeZone(name))
        } catch (e: ActionError) {
          res.put("ok", false).put("error", e.code).put("message", e.message)
        } catch (e: Exception) {
          res.put("ok", false).put("error", "failed").put("message", e.message ?: e.javaClass.simpleName)
        }
        promise.resolve(res.toString())
      }
    }

    // assistant numerique (appui long) : role perdu a chaque reinstallation, l'ecran Reglages le repropose
    Function("isDefaultAssistant") { Perms.assistant() }
    Function("openAssistantSettings") { Perms.openSettings(appContext.currentActivity ?: context, "assistant") }

    // ─── Declencheurs (§9.1) et controle d'apps (§9.2) : reglages ─────────
    Function("getEventSettings") { PhoneEvents.settingsJson() }
    Function("setEventSettings") { json: String -> PhoneEvents.setSettings(json) }  // rend ce qui est stocke
    Function("getUiWhitelist") { JSONArray(UiSettings.whitelist()).toString() }
    Function("setUiWhitelist") { json: String -> UiSettings.setWhitelist(json) }  // liste noire retiree, rend le stocke
    AsyncFunction("listApps") { promise: Promise ->
      Aura.pool.execute {
        promise.resolve(try { UiSettings.listApps() } catch (e: Exception) { "[]" })
      }
    }

    // Pause d'Aura (PauseLogic.kt) : etat applique localement, meme hors ligne ; until = epoch secondes, 0 = jusqu'a la reprise
    Function("getPauseState") { Pause.json() }
    Function("setPause") { mode: String, until: Double ->
      Pause.set(mode, until.toLong(), "téléphone")
      Pause.json()
    }

    Function("getLog") { Aura.logJson() }
    Function("clearLog") { Aura.clearLog() }

    Function("getConfirms") { Aura.confirms.listJson() }

    Function("getPermissions") { Perms.status().toString() }

    Function("permissionsChanged") {
      Aura.bridge.capsChanged()
    }

    Function("resolveConfirm") { id: String, ok: Boolean ->
      Aura.confirms.resolve(id, if (ok) "accepted" else "refused", "app")
    }

    AsyncFunction("pingWatch") { promise: Promise ->
      Aura.pool.execute {
        try {
          val ms = Aura.watch.ping()
          promise.resolve(JSONObject().put("ok", true).put("ms", ms).toString())
        } catch (e: ActionError) {
          promise.resolve(JSONObject().put("ok", false).put("error", e.message).toString())
        } catch (e: Exception) {
          promise.resolve(JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName).toString())
        }
      }
    }

    AsyncFunction("refreshWatch") { promise: Promise ->
      Aura.pool.execute {
        Aura.watch.refresh()
        promise.resolve(Aura.watch.stateJson().toString())
      }
    }

    Function("sendBridgeSettings") { json: String ->
      Aura.bridge.send(JSONObject(json).put("type", "settings_set"))
    }

    // asynchrone : la publication attend Play Services (15 s au plus), jamais sur le fil JS ; true = item enregistre
    AsyncFunction("setWatchSettings") { json: String ->
      val parsed = JSONObject(json)
      Aura.prefs.edit().putString("watch_settings", parsed.toString()).apply()
      Aura.watch.pushSettingsNow()
    }

    Function("getWatchSettings") { Aura.watchSettings().toString() }

    Function("openSettings") { kind: String ->
      Perms.openSettings(appContext.currentActivity ?: context, kind)
    }

    // lien aura://talk?autostart=1&n=<jeton> : le micro ne demarre que si le jeton vient de la tuile (usage unique, 30 s)
    Function("consumeTalkNonce") { n: String -> Aura.talkNonce.consume(n, System.currentTimeMillis()) }

    // revue du 27/10, F1 : App.tsx ne relaie a la PWA qu'un bouton de notification scelle par CE telephone
    Function("notifLinkOk") { url: String -> NotifLink.verify(Notifs.linkKey(), url) }

    Function("getPref") { key: String -> Aura.prefs.getString("js_$key", null) }

    Function("setPref") { key: String, value: String ->
      Aura.prefs.edit().putString("js_$key", value).apply()
    }

    Function("getInfo") {
      JSONObject()
        .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("android", Build.VERSION.RELEASE)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("version", Aura.version)
        .put("version_code", Aura.versionCode)
        .put("build", Aura.buildId)
        .toString()
    }

    AsyncFunction("runLocal") { action: String, params: String, promise: Promise ->
      Aura.pool.execute {
        val p = try { JSONObject(params.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
        promise.resolve(Aura.executor.runLocal(action, p).toString())
      }
    }
  }

  private fun clipboard(): android.content.ClipboardManager? =
    context.getSystemService(android.content.ClipboardManager::class.java)

  private fun copyText(text: String): Boolean {
    if (ClipLogic.copyRefusal(text, Pause.mode()) != null) return false
    val cm = clipboard() ?: return false
    cm.setPrimaryClip(android.content.ClipData.newPlainText(ClipLogic.OWN_LABEL, text.take(ClipLogic.COPY_MAX)))
    return true
  }

  // feuille de partage Android (Gmail, Outlook, WhatsApp…) ; « Objet : » de la reponse = sujet du mail
  private fun shareText(text: String, subject: String): Boolean {
    if (text.isBlank()) return false
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
      .putExtra(android.content.Intent.EXTRA_TEXT, text.take(100_000))
    if (subject.isNotBlank()) send.putExtra(android.content.Intent.EXTRA_SUBJECT, subject.take(200))
    val chooser = android.content.Intent.createChooser(send, "Envoyer avec…")
    val act = appContext.currentActivity
    return try {
      if (act != null) act.startActivity(chooser)
      else context.startActivity(chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
      true
    } catch (e: Exception) {
      false
    }
  }

  // JSON {"age_s": N} ou "" ; la description seule (pas d'avis Android), le repere avance a chaque examen
  private fun clipHint(): String {
    if (!Aura.appForeground) return ""
    val d = try { clipboard()?.primaryClipDescription } catch (e: Exception) { null } ?: return ""
    val stamp = if (Build.VERSION.SDK_INT >= 26) d.timestamp else 0L
    val seen = Aura.prefs.getLong(ClipLogic.PREF_SEEN, 0L)
    val isText = d.hasMimeType("text/*")
    val age = ClipLogic.hintAge(stamp, seen, System.currentTimeMillis(), isText, d.label?.toString())
    if (stamp > seen) Aura.prefs.edit().putLong(ClipLogic.PREF_SEEN, ClipLogic.nextSeen(stamp, seen)).apply()
    return if (age == null) "" else JSONObject().put("age_s", age).toString()
  }

  // le contenu, sur l'appui de l'utilisateur seulement (Android 12+ affiche alors son avis : attendu)
  private fun clipText(): String {
    if (!Aura.appForeground || !ClipLogic.readAllowed(Pause.mode())) return ""
    val clip = try { clipboard()?.primaryClip } catch (e: Exception) { null } ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(context)?.toString()?.take(ClipLogic.TEXT_MAX) ?: ""
  }

  private fun systemBars(light: Boolean, background: String) {
    val act = appContext.currentActivity ?: return
    val color = try { android.graphics.Color.parseColor(background) } catch (e: Exception) { null }
    act.runOnUiThread {
      val w = act.window
      val c = androidx.core.view.WindowCompat.getInsetsController(w, w.decorView)
      c.isAppearanceLightStatusBars = light
      c.isAppearanceLightNavigationBars = light
      if (color != null) w.decorView.setBackgroundColor(color)
    }
  }

  /** Ecran allume pendant une session vocale (FLAG_KEEP_SCREEN_ON sur la fenetre de l'activite). */
  private fun keepScreenOn(on: Boolean) {
    val act = appContext.currentActivity ?: return
    val keep = on || Aura.talk != null
    act.runOnUiThread {
      if (keep) act.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      else act.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
  }
}
