package dev.aura.mobile.device

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/*
 * Veille de reconnexion ecran eteint (03/10, PROTOCOL.md §19.4).
 *
 * Le probleme : le nouvel essai de BridgeClient (Aura.timer) compte en temps MONOTONE, qui s'arrete pendant le sommeil
 * profond. Un « nouvel essai dans 60 s » pose au debut d'un Doze partait des heures plus tard (s22 natif absent 2,7 h le
 * 29/09, 4,7 h le 30/09). c8a7e57 relance la socket a l'allumage de l'ecran ; ici, la meme chose ecran ETEINT.
 *
 * Moyen : UNE alarme AlarmManager inexacte `setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)` (horloge qui avance pendant le
 * sommeil ; ni permission d'alarme exacte, ni dependance WorkManager). Une seule alarme a la fois, remplacee a chaque pose :
 *  - hors ligne : 1, 2, 5, 10, 15 puis 30 min (backoff, remis a zero au welcome) -> kick() de la socket ;
 *  - en ligne : toutes les 15 min, un `ping` applicatif ; sans aucun message du bridge 20 s plus tard, la socket est jugee
 *    morte (NAT expire pendant le sommeil, Wi-Fi change) et relancee. Le bridge envoie de lui-meme une trame ping toutes les
 *    30 s : cette veille ne sert que quand la socket est morte SANS que personne ne le voie.
 * Pendant un passage : verrou de veille partiel de 25 s au plus (le temps du pong ou de l'ouverture TLS), relache des que
 * c'est tranche.
 *
 * Cout (estimation, non mesure sur le S22) : en ligne, 4 reveils par heure au plus, ~1 s de CPU et ~200 octets chacun sur
 * une socket deja ouverte ; en Doze profond Android regroupe ces alarmes dans ses fenetres de maintenance (au plus une toutes
 * les ~9 a 15 min par app), donc souvent moins. Hors ligne : 5 essais la premiere heure, puis 2 par heure. A comparer aux
 * trames ping du bridge toutes les 30 s, deja la, qui coutent bien davantage. Ordre de grandeur : moins de 1 % de batterie
 * par jour.
 */
object WatchdogLogic {
  const val ONLINE_CHECK_MS = 15 * 60_000L
  const val PONG_WAIT_MS = 20_000L
  const val WAKE_MAX_MS = 25_000L
  val OFFLINE_STEPS_MS = longArrayOf(60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L)

  /** Delai de l'alarme apres le `attempt`-ieme echec d'affilee (0 = premier). */
  fun offlineDelay(attempt: Int): Long = OFFLINE_STEPS_MS[attempt.coerceIn(0, OFFLINE_STEPS_MS.size - 1)]

  /** La socket est morte : un ping est parti a `pingAt` et rien n'est arrive depuis (lastRx = dernier message recu). */
  fun dead(pingAt: Long, lastRx: Long, now: Long): Boolean = now - pingAt >= PONG_WAIT_MS && lastRx < pingAt
}

object BridgeWatchdog {
  private const val REQ = 4810
  const val ACTION = "dev.aura.mobile.WATCHDOG"

  @Volatile private var attempts = 0

  private fun pending(ctx: Context, create: Boolean): PendingIntent? {
    val i = Intent(ctx, WatchdogReceiver::class.java).setAction(ACTION)
    val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
    return PendingIntent.getBroadcast(ctx, REQ, i, flags)
  }

  private fun schedule(delayMs: Long) {
    try {
      val ctx = Aura.app
      val am = ctx.getSystemService(AlarmManager::class.java) ?: return
      val pi = pending(ctx, true) ?: return
      am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pi)
    } catch (e: Exception) {
      Log.w(Aura.TAG, "veille de reconnexion non planifiee", e)
    }
  }

  /** welcome recu : backoff remis a zero, prochain controle dans 15 min. */
  fun onOnline() {
    attempts = 0
    schedule(WatchdogLogic.ONLINE_CHECK_MS)
  }

  /** Socket tombee (BridgeClient.down), reconnexion voulue : filet en temps reel, en plus du minuteur monotone. */
  fun onOffline() {
    val delay = WatchdogLogic.offlineDelay(attempts)
    if (attempts < WatchdogLogic.OFFLINE_STEPS_MS.size) attempts++
    schedule(delay)
  }

  /** Service arrete (ou jeton refuse) : plus de veille. */
  fun cancel() {
    try {
      val ctx = Aura.app
      val pi = pending(ctx, false) ?: return
      ctx.getSystemService(AlarmManager::class.java)?.cancel(pi)
      pi.cancel()
    } catch (e: Exception) {
      // deja annulee
    }
  }

  /** Thread du recepteur ; `done` libere le verrou de veille et le goAsync. */
  fun onAlarm(ctx: Context, done: () -> Unit) {
    Aura.init(ctx)
    if (!Aura.prefs.getBoolean("service_enabled", false) || Aura.token.isEmpty()) {
      done()
      return
    }
    if (!Aura.serviceRunning) {
      // processus relance par l'alarme (service tue) : le service rouvre la socket, son welcome reposera la veille
      AuraService.start(ctx, false)
      schedule(WatchdogLogic.offlineDelay(attempts))
      Aura.timer.schedule(Runnable { done() }, 10, TimeUnit.SECONDS)
      return
    }
    val bridge = Aura.bridge
    when (bridge.status) {
      "online" -> {
        val pingAt = System.currentTimeMillis()
        if (!bridge.send(JSONObject().put("type", "ping"))) {
          bridge.recycle("veille : envoi du ping impossible")
          done()
          return
        }
        Aura.timer.schedule(Runnable {
          try {
            if (bridge.online() && WatchdogLogic.dead(pingAt, bridge.lastRxAt, System.currentTimeMillis())) {
              Log.i(Aura.TAG, "veille : aucun pong en ${WatchdogLogic.PONG_WAIT_MS / 1000} s, socket relancee")
              bridge.recycle("veille : socket muette")
            } else if (bridge.online()) {
              schedule(WatchdogLogic.ONLINE_CHECK_MS)
              Usage.maybeReport()
            }
          } finally {
            done()
          }
        }, WatchdogLogic.PONG_WAIT_MS, TimeUnit.MILLISECONDS)
      }
      "rejected", "stopped" -> done()  // jeton refuse : rien a relancer sans l'utilisateur ; arret voulu
      else -> {
        // hors ligne ou connexion en cours : on relance tout de suite ; un nouvel echec repose l'alarme (down)
        Aura.io.execute { bridge.kick() }
        schedule(WatchdogLogic.offlineDelay(attempts))  // filet si la tentative reste pendue
        Aura.timer.schedule(Runnable { done() }, 15, TimeUnit.SECONDS)
      }
    }
  }
}

/** Alarme de la veille : verrou de veille borne + goAsync, le travail part sur les fils d'Aura. */
class WatchdogReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != BridgeWatchdog.ACTION) return
    val pending = goAsync()
    val wl = try {
      context.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aura:bridge-watchdog")
        ?.apply { setReferenceCounted(false); acquire(WatchdogLogic.WAKE_MAX_MS) }
    } catch (e: Exception) {
      null
    }
    val finished = java.util.concurrent.atomic.AtomicBoolean(false)
    val done = {
      if (finished.compareAndSet(false, true)) {
        try { if (wl?.isHeld == true) wl.release() } catch (e: Exception) { /* deja relache */ }
        try { pending.finish() } catch (e: Exception) { /* deja termine */ }
      }
    }
    try {
      BridgeWatchdog.onAlarm(context, done)
    } catch (e: Exception) {
      Log.w(Aura.TAG, "veille de reconnexion en echec", e)
      done()
    }
  }
}
