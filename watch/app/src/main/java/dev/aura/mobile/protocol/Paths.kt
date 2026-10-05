package dev.aura.mobile.protocol

/** Chemins et capabilities du Wearable Data Layer (PROTOCOL.md section 3). */
object Paths {
  const val CAPABILITY_WATCH = "aura_watch"
  const val CAPABILITY_PHONE = "aura_phone"

  const val VOICE_PREFIX = "/aura/voice/"
  const val CHAT = "/aura/chat"
  const val STATE = "/aura/state"
  const val REPLY = "/aura/reply"
  const val AUDIO_PREFIX = "/aura/audio/"
  const val CONFIRM_REQUEST = "/aura/confirm_request"
  const val CONFIRM_RESPONSE = "/aura/confirm_response"
  const val CONFIRM_CANCEL = "/aura/confirm_cancel"
  const val CMD = "/aura/cmd"
  const val CMD_RESULT = "/aura/cmd_result"
  const val SETTINGS = "/aura/settings"
  const val PING = "/aura/ping"
  /** « Reprendre une conversation » (28/09) : montre -> S22 `{}` puis S22 -> montre la liste ; choix `{conv?}`. */
  const val CONVS_REQUEST = "/aura/convs_request"
  const val CONVS = "/aura/convs"
  const val CONV_SELECT = "/aura/conv_select"
  /** Onglet neuf : `{req}` ; le S22 le crée au bridge, le sélectionne et renvoie `/aura/convs` avec `created = req`. */
  const val CONV_NEW = "/aura/conv_new"
  const val PONG = "/aura/pong"
  /** Pause d'Aura (§10) : montre -> S22 `{mode, until?}` ; etat publie par le S22 dans le DataItem `/aura/status`. */
  const val PAUSE_SET = "/aura/pause_set"
  const val STATUS = "/aura/status"
  /** Fiche d'appel (§10) : S22 -> montre `{title, lines, number?, ts}`, affichee en notification (texte seul). */
  const val CALL_CARD = "/aura/call_card"

  fun voice(id: String) = VOICE_PREFIX + id

  /** Extrait l'id d'un chemin `/aura/audio/<id>`, null si le chemin ne correspond pas. */
  fun audioId(path: String): String? =
    if (path.startsWith(AUDIO_PREFIX)) path.removePrefix(AUDIO_PREFIX).takeIf { it.isNotBlank() && !it.contains('/') } else null
}
