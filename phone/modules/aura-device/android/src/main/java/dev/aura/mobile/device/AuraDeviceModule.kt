package dev.aura.mobile.device

import android.content.Context
import android.os.Build
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
      Usage.maybeReport()  // usage_report : au plus une fois par jour, des nombres seulement (PROTOCOL.md §11.18)
    }

    Function("configure") { url: String, token: String, poste: String, version: String ->
      val e = Aura.prefs.edit()
      if (url.isNotBlank()) e.putString("url", url)
      if (token.isNotBlank()) e.putString("token", token)
      if (poste.isNotBlank()) e.putString("poste", poste)
      e.putString("version", version).apply()
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
      val s = TalkSession { json -> Aura.emit("onTalk", json.toString()) }
      Aura.talk = s
      s.start()
      keepScreenOn(true)
      ""
    }

    Function("talkTap") { Aura.talk?.tap() }

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

    Function("canPostPromoted") { LiveUpdate.canPromote() }

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

    Function("setWatchSettings") { json: String ->
      val parsed = JSONObject(json)
      Aura.prefs.edit().putString("watch_settings", parsed.toString()).apply()
      Aura.watch.pushSettings()
    }

    Function("getWatchSettings") { Aura.watchSettings().toString() }

    Function("openSettings") { kind: String ->
      Perms.openSettings(appContext.currentActivity ?: context, kind)
    }

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

  /** Ecran allume pendant une session vocale (FLAG_KEEP_SCREEN_ON sur la fenetre de l'activite). */
  private fun keepScreenOn(on: Boolean) {
    val act = appContext.currentActivity ?: return
    act.runOnUiThread {
      if (on) act.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      else act.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
  }
}
