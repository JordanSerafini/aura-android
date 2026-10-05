package dev.aura.mobile.device

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * « Pause d'Aura » (logique : PauseLogic.kt, contrat : docs/PROTOCOL.md §10). L'etat vit dans les prefs du
 * processus, PAS dans le JS : il s'applique app fermee et hors ligne. Le bridge reste l'autorite serveur ; ici on
 * refuse localement (ActionExecutor, AuraAccessibilityService, PhoneEvents) puis on previent le bridge.
 */
object Pause {
  private const val PREF = "pause_state"
  private const val TOGGLE_S = PauseLogic.DEFAULT_PAUSE_S  // bouton de la notification et de la montre : apps, 1 h

  @Volatile private var cached: PauseState? = null
  private var expiry: ScheduledFuture<*>? = null
  /** Bridge d'avant la pause : `bad_type` cite pause_set. L'etat local s'applique quand meme. */
  @Volatile var bridgeUnsupported = false

  private fun nowS() = System.currentTimeMillis() / 1000

  fun state(): PauseState = cached ?: PauseState.fromJson(Aura.prefs.getString(PREF, null)).also { cached = it }

  /** Mode EN VIGUEUR (une pause echue vaut off). */
  fun mode(): String = state().effectiveMode(nowS())

  fun active(): Boolean = mode() != PauseMode.OFF
  fun blocksApps(): Boolean = PauseLogic.blocksApps(mode())
  fun blocksEvents(): Boolean = PauseLogic.blocksEvents(mode())

  /** Leve ActionError("paused") si cette action est refusee dans le mode en vigueur. */
  fun check(action: String) {
    val st = state()
    val m = st.effectiveMode(nowS())
    if (PauseLogic.blocksAction(m, action)) throw PauseLogic.refusal(m, st.until)
  }

  fun json(): String = state().toWire(nowS()).put("pending", state().pending).toString()

  fun describe(): String = PauseLogic.describe(state(), nowS())

  private fun save(st: PauseState) {
    cached = st
    Aura.prefs.edit().putString(PREF, st.toJson().toString()).apply()
    scheduleExpiry()
  }

  /** Au demarrage du processus : relance la minuterie d'echeance d'une pause deja posee. */
  fun start() {
    state()  // charge l'etat des prefs : scheduleExpiry lit le cache
    scheduleExpiry()
  }

  private fun scheduleExpiry() {
    expiry?.cancel(false)
    expiry = null
    val st = cached ?: return
    if (st.mode == PauseMode.OFF || st.until <= 0L) return
    val delayMs = (st.until * 1000 - System.currentTimeMillis()).coerceAtLeast(0L) + 50
    expiry = Aura.timer.schedule(Runnable { expire() }, delayMs, TimeUnit.MILLISECONDS)
  }

  /** Echeance atteinte : on repasse a `off` localement ; le bridge fait la meme chose et le diffuse. */
  private fun expire() {
    val st = state()
    if (st.mode == PauseMode.OFF || st.until <= 0L || nowS() < st.until) return
    save(PauseState(PauseMode.OFF, 0L, "expiration", System.currentTimeMillis(), pending = false))
    Log.i(Aura.TAG, "pause echue : reprise")
    changed()
  }

  /**
   * l'utilisateur (carte Reglages, notification) ou la montre change la pause : appliquee TOUT DE SUITE, meme hors ligne ;
   * renvoyee au bridge (au welcome si la socket est coupee, `pending`). Rend l'etat stocke.
   */
  fun set(mode: String, untilS: Long, by: String): PauseState {
    val (m, until) = PauseLogic.normalize(mode, untilS, nowS())
    val st = PauseState(m, until, by, System.currentTimeMillis(), pending = !bridgeUnsupported)
    save(st)
    Usage.count(if (m == PauseMode.OFF) "pause.levee" else "pause.activee")
    sendToBridge(st)
    changed()
    return st
  }

  /** Bouton « Pause / Reprendre » (notification, montre) : pause apps 1 h, ou reprise. */
  fun toggle(by: String): PauseState =
    if (active()) set(PauseMode.OFF, 0L, by) else set(PauseMode.APPS, nowS() + TOGGLE_S, by)

  private fun sendToBridge(st: PauseState): Boolean {
    val msg = JSONObject().put("type", "pause_set").put("mode", st.mode)
    if (st.until > 0L && st.mode != PauseMode.OFF) msg.put("until", st.until)
    return Aura.bridge.send(msg)
  }

  /** `pause_state` du bridge (changement, expiration, autre appareil) : applique tel quel. */
  fun onBridge(msg: JSONObject) {
    val remote = PauseLogic.fromMessage(msg) ?: return
    val now = nowS()
    val effective = if (remote.active(now)) remote else PauseState(PauseMode.OFF, 0L, remote.by)
    adopt(effective, System.currentTimeMillis())
  }

  /** `welcome` : l'etat du bridge, ou notre changement hors ligne qui l'emporte (dernier changement gagne). */
  fun onWelcome(msg: JSONObject) {
    val remote = PauseLogic.fromWelcome(msg)
    val local = state()
    if (remote == null) {
      // bridge sans pause (ou welcome muet) : un changement local en attente est quand meme renvoye, il dira bad_type sinon
      if (local.pending && !bridgeUnsupported) sendToBridge(local)
      return
    }
    val stamp = (msg.optJSONObject("pause") ?: msg.optJSONObject("pause_state"))?.let { PauseLogic.remoteStampMs(it) }
    val now = nowS()
    if (PauseLogic.sameAs(local, remote, now)) {
      if (local.pending) save(local.copy(pending = false))
      return
    }
    when (PauseLogic.reconcile(local, stamp)) {
      PauseLogic.Decision.PUSH_LOCAL -> sendToBridge(local)
      PauseLogic.Decision.ADOPT_REMOTE ->
        adopt(if (remote.active(now)) remote else PauseState(PauseMode.OFF, 0L, remote.by), stamp ?: System.currentTimeMillis())
    }
  }

  private fun adopt(remote: PauseState, atMs: Long) {
    val before = state()
    val next = remote.copy(changedAt = atMs, pending = false)
    save(next)
    if (!PauseLogic.sameAs(before, next, nowS()) || before.pending) changed()
  }

  /** `error bad_type` qui cite pause_set : le bridge ne connait pas encore la pause ; l'etat local continue de valoir. */
  fun onBridgeUnsupported() {
    bridgeUnsupported = true
    val st = state()
    if (st.pending) save(st.copy(pending = false))
  }

  fun onConnected() {
    bridgeUnsupported = false
  }

  private fun changed() {
    Aura.emit("onPause", json())
    AuraService.refreshNotification()
    WatchStatus.push()
  }
}
