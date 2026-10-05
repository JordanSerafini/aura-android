package dev.aura.mobile.device

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Etat publie vers la montre pour sa tuile et sa complication (DataItem `/aura/status`, docs/PROTOCOL.md §10) :
 * pause d'Aura, confirmations en attente, etat du lien avec le bridge. DataItem plutot que message : la montre le
 * relit meme si le telephone etait injoignable au moment du changement. Publie seulement quand le contenu change
 * (`updated` n'entre pas dans la comparaison), et seulement si une montre est reliee. Battement : republie quand meme
 * toutes les 90 s, pour que la montre sache l'age de l'etat (au-dela de 5 min elle l'affiche « inconnu », jamais
 * « Connecte » : StatusLogic.STALE_S cote montre).
 */
object WatchStatus {
  /** Doit rester bien en dessous de StatusLogic.STALE_S (montre, 300 s). */
  const val HEARTBEAT_S = 90L

  @Volatile private var lastKey: String? = null
  private var heartbeat: java.util.concurrent.ScheduledFuture<*>? = null

  /** Au demarrage du processus : le battement qui garde `updated` frais cote montre. */
  @Synchronized
  fun start() {
    if (heartbeat != null) return
    heartbeat = Aura.timer.scheduleWithFixedDelay({
      try { push(force = true) } catch (e: Exception) { Log.w(Aura.TAG, "battement montre", e) }
    }, HEARTBEAT_S, HEARTBEAT_S, TimeUnit.SECONDS)
  }

  fun json(nowS: Long = System.currentTimeMillis() / 1000): JSONObject = JSONObject()
    .put("pause", Pause.state().toWire(nowS))
    .put("confirms", Aura.confirms.count())
    // appels manques a rappeler et temps a valider (01/10 soir) : des nombres, la complication les affiche
    .put("missed", MissedCalls.groups())
    .put("times", TimeProposals.count())
    .put("bridge", Aura.bridge.status)

  fun push(force: Boolean = false) {
    if (!Aura.watch.connected()) return
    val body = json()
    val key = body.toString()
    if (!force && key == lastKey) return
    lastKey = key
    Aura.pool.execute {
      try {
        body.put("updated", System.currentTimeMillis())
        val req = PutDataRequest.create("/aura/status").setData(body.toString().toByteArray(Charsets.UTF_8)).setUrgent()
        Tasks.await(Wearable.getDataClient(Aura.app).putDataItem(req), 15, TimeUnit.SECONDS)
      } catch (e: Exception) {
        lastKey = null  // pas parti : la prochaine occasion retentera
        Log.w(Aura.TAG, "etat montre non publie", e)
      }
    }
  }
}
