package dev.aura.mobile.data

import dev.aura.mobile.audio.VolumeLevel
import dev.aura.mobile.protocol.AuraStatus
import dev.aura.mobile.protocol.ConfirmRequest
import dev.aura.mobile.protocol.ConvList
import dev.aura.mobile.protocol.NewConv
import dev.aura.mobile.protocol.WatchSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** État de la conversation en cours, partagé entre le service Data Layer et l'interface (même processus). */
data class Conversation(
  val id: String? = null,
  /** État protocole (`sent`, `thinking`...) ou état local (`recording`, `sending`, `phone_unreachable`, `no_answer`). */
  val state: String? = null,
  val stateText: String? = null,
  val reply: String = "",
  val final: Boolean = false,
  val updatedAt: Long = 0L,
)

data class PendingConfirm(val request: ConfirmRequest, val deadlineMs: Long)

object AuraBus {
  val conversation = MutableStateFlow(Conversation())
  val settings = MutableStateFlow(WatchSettings())
  val lastAudioPath = MutableStateFlow<String?>(null)
  /** Dernier etat publie par le telephone (pause, confirmations en attente, lien bridge) ; null = jamais recu. */
  val status = MutableStateFlow<AuraStatus?>(null)
  val playing = MutableStateFlow(false)
  val phoneReachable = MutableStateFlow<Boolean?>(null)
  val confirm = MutableStateFlow<PendingConfirm?>(null)
  /** Onglets ouverts relayés par le S22 ; null = jamais reçus (le téléphone n'a pas encore répondu). */
  val convs = MutableStateFlow<ConvList?>(null)
  /** Heure (ms) de la dernière `/aura/convs` reçue : l'écran Conversations y lit si le S22 a répondu. */
  val convsReceivedAt = MutableStateFlow(0L)
  /** « Nouvelle conversation » en cours ou échouée (null = rien à afficher). */
  val newConv = MutableStateFlow<NewConv?>(null)
  /** Niveau du volume de lecture (libellé via VolumeLogic.label) ; null = pas encore lu. */
  val volume = MutableStateFlow<VolumeLevel?>(null)
  /** Relance de l'app (tuile, notification) : l'interface revient à l'écran principal. */
  val goHome = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

  fun setState(id: String?, state: String, text: String? = null) {
    conversation.update { c ->
      val sameId = id == null || id.isEmpty() || id == c.id
      if (sameId) {
        c.copy(state = state, stateText = text, updatedAt = System.currentTimeMillis())
      } else {
        Conversation(id = id, state = state, stateText = text, updatedAt = System.currentTimeMillis())
      }
    }
  }

  fun setReply(id: String, text: String, final: Boolean) {
    conversation.update { c ->
      val base = if (id.isEmpty() || id == c.id) c else Conversation(id = id)
      base.copy(reply = text, final = final, updatedAt = System.currentTimeMillis())
    }
  }

  /** Efface un état local laissé orphelin (enregistrement coupé par la destruction de l'activité). */
  fun clearState(id: String, state: String) {
    conversation.update { c -> if (c.id == id && c.state == state) c.copy(state = null, updatedAt = System.currentTimeMillis()) else c }
  }

  /** Nouvelle requête lancée depuis la montre : on repart d'une conversation vide. */
  fun startLocal(id: String, state: String) {
    conversation.value = Conversation(id = id, state = state, updatedAt = System.currentTimeMillis())
  }
}
