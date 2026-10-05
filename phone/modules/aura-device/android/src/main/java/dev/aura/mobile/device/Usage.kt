package dev.aura.mobile.device

import android.util.Log
import java.time.LocalDate

/**
 * Compteurs d'usage locaux (PROTOCOL.md §11.18, logique : UsageLogic.kt). Des NOMBRES : écrans ouverts, tuile, boutons de
 * notification, bascules de pause, fiches d'appel, événements envoyés / filtrés par type. Jamais un titre, un nom, un numéro.
 *
 * Envoi : `usage_report` au plus UNE fois par jour (le bridge additionne : on envoie les incréments depuis le dernier envoi,
 * puis on les efface). Déclencheurs : passage de l'app en arrière-plan, et depuis le 03/10 aussi 30 s après chaque `welcome` et
 * à chaque passage de la veille de reconnexion (BridgeWatchdog). Mesure du 03/10 : le bridge n'avait JAMAIS reçu un compteur
 * natif du S22 (`context/usage_counters.json` : seuls ceux de la PWA, même nom d'appareil) ; un seul déclencheur, lié au cycle
 * de vie de l'activité, ne suffisait pas : le service tourne surtout app fermée. Bridge sans `usage_report` (`error bad_type`) : ce qui est parti pour
 * rien est remis, et plus rien ne part jusqu'à la reconnexion.
 *
 * Noms utilisés (`[a-z0-9_.]{1,40}`) :
 *   ecran.aura | telephone | actions | montre | reglages | journal | talk
 *   tuile.dicter
 *   notif.rappeler | tout_valider | annuler_reprise | copier
 *   time.confirme | time.annule
 *   pause.activee | pause.levee
 *   fiche_appel.affichee
 *   evt.envoye.<type> | evt.filtre.<type> (aucune règle active) | evt.antispam.<type>
 */
object Usage {
  private const val PREF_BUCKETS = "usage_buckets"
  private const val PREF_LAST_DAY = "usage_last_report_day"

  private val lock = Any()
  private var buckets: UsageBuckets? = null
  private var lastSent: List<UsageLogic.Report> = emptyList()
  @Volatile var unsupported = false

  private fun loaded(): UsageBuckets = buckets ?: UsageLogic.fromJson(Aura.prefs.getString(PREF_BUCKETS, null)).also { buckets = it }

  private fun persist() {
    Aura.prefs.edit().putString(PREF_BUCKETS, UsageLogic.toJson(loaded())).apply()
  }

  /** Incrémente un compteur ; un nom invalide est ignoré sans bruit (compter ne casse jamais rien). */
  fun count(name: String, n: Int = 1) {
    try {
      synchronized(lock) {
        val today = LocalDate.now()
        val b = loaded()
        if (UsageLogic.add(b, today.toString(), name, n)) {
          UsageLogic.prune(b, today)
          persist()
        }
      }
    } catch (e: Exception) {
      Log.w(Aura.TAG, "compteur $name non ecrit", e)
    }
  }

  /** Au passage en arrière-plan : un envoi s'il est dû (rien parti aujourd'hui, quelque chose à dire, bridge joignable). */
  fun maybeReport() {
    Aura.io.execute {
      try {
        synchronized(lock) {
          val today = LocalDate.now()
          if (unsupported || !Aura.bridge.online()) return@execute
          val b = loaded()
          if (!UsageLogic.due(Aura.prefs.getString(PREF_LAST_DAY, null), today.toString(), b)) return@execute
          val all = UsageLogic.reports(b, Aura.bridge.deviceName ?: Aura.poste, today)
          if (all.isEmpty()) return@execute
          // le bridge refuse au-dela de 6 usage_report par minute et par socket : 5 jours au plus par passage, les plus anciens
          // d'abord ; s'il en reste, le jour n'est pas marque « envoye » et le passage suivant continue
          val reports = all.take(UsageLogic.MAX_REPORTS_PER_PASS)
          val sent = reports.filter { Aura.bridge.send(it.message) }
          if (sent.isEmpty()) return@execute
          UsageLogic.subtract(b, sent)
          lastSent = sent
          persist()
          if (sent.size == all.size) Aura.prefs.edit().putString(PREF_LAST_DAY, today.toString()).apply()
          Log.i(Aura.TAG, "usage_report envoye (${sent.size} jour(s))")  // jamais les noms ni les valeurs
        }
      } catch (e: Exception) {
        Log.w(Aura.TAG, "usage_report non envoye", e)
      }
    }
  }

  /** Après un `welcome` : un envoi dû part 30 s plus tard (laisser passer le rattrapage de la connexion). */
  fun maybeReportSoon() {
    try {
      Aura.timer.schedule(Runnable { maybeReport() }, 30, java.util.concurrent.TimeUnit.SECONDS)
    } catch (e: Exception) {
      Log.w(Aura.TAG, "usage_report non planifie", e)
    }
  }

  /** `error bad_type` citant usage_report : le bridge ne connaît pas encore le message. On remet ce qui est parti. */
  fun onUnsupported() {
    synchronized(lock) {
      unsupported = true
      if (lastSent.isEmpty()) return
      UsageLogic.restore(loaded(), lastSent)
      lastSent = emptyList()
      Aura.prefs.edit().remove(PREF_LAST_DAY).apply()  // renvoyé dès que le bridge saura
      persist()
    }
  }

  fun onWelcome() {
    unsupported = false
  }
}
