package dev.aura.mobile.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Etat publie par le telephone pour la tuile et la complication (DataItem `/aura/status`, PROTOCOL.md §10) et
 * « Pause d'Aura » (`/aura/pause_set`, montre -> telephone). Logique pure, testee en JVM (StatusTest).
 */

object PauseModes {
  const val OFF = "off"
  const val APPS = "apps"
  const val ALL = "all"
  fun valid(m: String?) = m == OFF || m == APPS || m == ALL
}

/** `until` : epoch secondes, 0 = jusqu'a la reprise. */
@Serializable
data class PauseWire(val mode: String = PauseModes.OFF, val until: Long = 0L, val by: String = "")

@Serializable
data class AuraStatus(
  val pause: PauseWire = PauseWire(),
  /** Confirmations en attente cote telephone (toutes surfaces confondues). */
  val confirms: Int = 0,
  /** Lien telephone <-> bridge : stopped | connecting | online | offline | rejected. */
  val bridge: String = "offline",
  val updated: Long = 0L,
  /** Numeros a rappeler (appels manques non rappeles, PROTOCOL.md §11.10) ; absent d'un telephone plus ancien = 0. */
  val missed: Int = 0,
  /** Temps non saisis a valider (PROTOCOL.md §11.11) ; absent d'un telephone plus ancien = 0. */
  val times: Int = 0,
)

/** `/aura/pause_set` : montre -> telephone. */
@Serializable
data class PauseSet(val mode: String, val until: Long? = null)

object StatusLogic {
  const val PAUSE_DEFAULT_S = 60 * 60L

  /**
   * Au-dela, l'etat du telephone est PERIME : le telephone republie au moins toutes les 90 s tant qu'il est relie
   * (WatchStatus.HEARTBEAT_S), donc 5 min sans nouvelles = telephone eteint, hors de portee ou app arretee. Une tuile
   * qui afficherait « Connecte » depuis un etat d'il y a une heure mentirait.
   */
  const val STALE_S = 5 * 60L

  /** `updated` (ms, horloge du telephone) absent ou trop ancien. Pas d'etat du tout = pas perime, simplement inconnu. */
  fun stale(status: AuraStatus?, nowS: Long): Boolean {
    if (status == null) return false
    return status.updated <= 0L || nowS - status.updated / 1000 > STALE_S
  }

  /** Secondes avant que cet etat devienne perime (> 0), null s'il l'est deja ou s'il n'y a pas d'etat. */
  fun staleInS(status: AuraStatus?, nowS: Long): Long? {
    if (status == null || stale(status, nowS)) return null
    return status.updated / 1000 + STALE_S - nowS + 1
  }

  /** Mode de pause EN VIGUEUR : une pause echue vaut `off` meme si le telephone n'a pas republie. */
  fun pauseMode(status: AuraStatus?, nowS: Long): String {
    val p = status?.pause ?: return PauseModes.OFF
    if (!PauseModes.valid(p.mode) || p.mode == PauseModes.OFF) return PauseModes.OFF
    return if (p.until > 0L && nowS >= p.until) PauseModes.OFF else p.mode
  }

  fun parse(bytes: ByteArray?): AuraStatus? = Protocol.decode<AuraStatus>(bytes)

  /** Bouton « Pause » de la tuile : bascule `apps` pendant 1 h, puis `off`. */
  fun togglePause(status: AuraStatus?, nowS: Long): PauseSet =
    if (pauseMode(status, nowS) == PauseModes.OFF) PauseSet(PauseModes.APPS, nowS + PAUSE_DEFAULT_S) else PauseSet(PauseModes.OFF, null)

  /**
   * Etat optimiste apres un envoi reussi : la tuile suit le doigt sans attendre le `/aura/status` du telephone.
   * `updated` n'est PAS rafraichi : seule la pause vient d'etre confirmee, le reste (lien, confirmations) garde son age,
   * sinon un vieil etat « Connecte » repartirait pour 5 min de fraicheur.
   */
  fun applied(status: AuraStatus?, set: PauseSet): AuraStatus {
    val base = status ?: AuraStatus()
    val pause = if (set.mode == PauseModes.OFF) PauseWire(PauseModes.OFF, 0L, "montre") else PauseWire(set.mode, set.until ?: 0L, "montre")
    return base.copy(pause = pause)
  }

  enum class Tone { OK, WARN, BAD, DIM }

  /** Ce que la tuile affiche. */
  data class TileModel(
    val headline: String,
    val tone: Tone,
    val confirms: Int,
    val paused: Boolean,
    /** Libelle du bouton : « Pause » ou « Reprendre ». */
    val pauseButton: String,
  )

  /**
   * phoneReachable : lien Data Layer montre -> telephone (null = pas encore lu). Priorite du bandeau :
   * telephone injoignable, pause, etat perime (« État inconnu », jamais « Connecté » d'avant), bridge hors ligne,
   * sinon « Connecté ».
   */
  fun tile(status: AuraStatus?, phoneReachable: Boolean?, nowS: Long): TileModel {
    val mode = pauseMode(status, nowS)
    val paused = mode != PauseModes.OFF
    val confirms = (status?.confirms ?: 0).coerceAtLeast(0)
    val button = if (paused) "Reprendre" else "Pause"
    val (headline, tone) = when {
      phoneReachable == false -> "Téléphone injoignable" to Tone.BAD
      paused -> (if (mode == PauseModes.ALL) "En pause totale" else "En pause (apps)") to Tone.WARN
      status == null -> "Aura" to Tone.DIM
      stale(status, nowS) -> "État inconnu" to Tone.DIM
      status.bridge == "online" -> "Connecté" to Tone.OK
      status.bridge == "connecting" -> "Connexion…" to Tone.DIM
      status.bridge == "rejected" -> "Appareil refusé" to Tone.BAD
      else -> "PC hors ligne" to Tone.BAD
    }
    return TileModel(headline, tone, confirms, paused, button)
  }

  /** Complication courte : texte (7 car. au plus), titre, description d'accessibilite. */
  data class ComplicationModel(val text: String, val title: String, val description: String)

  /** Nombre de choses a faire (numeros a rappeler + temps a valider), positif ou nul. */
  fun todo(status: AuraStatus?): Int = (status?.missed ?: 0).coerceAtLeast(0) + (status?.times ?: 0).coerceAtLeast(0)

  private fun count(n: Int) = if (n > 99) "99+" else n.toString()

  /** Titre et description de la complication quand il y a des appels a rappeler et / ou des temps a valider. */
  fun todoModel(status: AuraStatus?): ComplicationModel {
    val missed = (status?.missed ?: 0).coerceAtLeast(0)
    val times = (status?.times ?: 0).coerceAtLeast(0)
    val parts = buildList {
      if (missed > 0) add(if (missed == 1) "1 appel à rappeler" else "$missed appels à rappeler")
      if (times > 0) add(if (times == 1) "1 temps à valider" else "$times temps à valider")
    }
    val title = when {
      missed > 0 && times > 0 -> "À traiter"
      missed > 0 -> "À rappeler"
      else -> "Temps"
    }
    return ComplicationModel(count(missed + times), title, parts.joinToString(", "))
  }

  /**
   * « Hors » = telephone injoignable ou PC hors ligne ; « ? » = etat jamais recu ou perime (on ne dit pas « OK » sur
   * une information qu'on n'a pas). La pause prime sur le reste tant que le telephone est joignable.
   */
  fun complication(status: AuraStatus?, phoneReachable: Boolean?, nowS: Long): ComplicationModel {
    val mode = pauseMode(status, nowS)
    val confirms = (status?.confirms ?: 0).coerceAtLeast(0)
    return when {
      phoneReachable == false -> ComplicationModel("Hors", "Aura", "Téléphone injoignable, ouvrir")
      mode != PauseModes.OFF -> ComplicationModel("Pause", "Aura", "Aura en pause, ouvrir")
      status == null || stale(status, nowS) -> ComplicationModel("?", "Aura", "État d'Aura inconnu, ouvrir")
      confirms > 0 -> ComplicationModel(if (confirms > 99) "99+" else confirms.toString(), "À valider",
        if (confirms == 1) "1 confirmation en attente" else "$confirms confirmations en attente")
      // appels manques a rappeler et temps a valider (01/10 soir) : une confirmation demandee a l'utilisateur passe avant, la pause et
      // l'etat inconnu aussi ; le PC hors ligne ne cache pas ce qui reste a faire
      todo(status) > 0 -> todoModel(status).let { it.copy(description = it.description + ", ouvrir") }
      status.bridge != "online" -> ComplicationModel("Hors", "Aura", "Aura hors ligne, ouvrir")
      else -> ComplicationModel("OK", "Aura", "Aura connectée, ouvrir")
    }
  }
}

/**
 * Clics de la tuile. Le `lastClickableId` d'une TileRequest est REMANENT : il reste celui du dernier appui tant qu'un
 * autre n'a pas eu lieu, et chaque `requestUpdate` (nouveau `/aura/status`, fraicheur) le rejoue. Lire « pause » a chaque
 * rendu rebasculerait donc la pause sans que l'utilisateur ait rien touche. Chaque rendu porte un id unique (`pause:<nonce>`) ;
 * un id deja traite est ignore.
 */
object TileClicks {
  private const val PAUSE_PREFIX = "pause:"

  fun pauseId(nonce: Long): String = PAUSE_PREFIX + nonce

  /** Nonce du clic « Pause » a traiter, null s'il n'y en a pas ou s'il est deja traite (`lastHandled`). */
  fun pausePending(lastClickableId: String?, lastHandled: Long): Long? {
    val id = lastClickableId ?: return null
    if (!id.startsWith(PAUSE_PREFIX)) return null
    val nonce = id.substring(PAUSE_PREFIX.length).toLongOrNull() ?: return null
    return if (nonce == lastHandled) null else nonce
  }
}
