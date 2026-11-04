package dev.aura.mobile.device

/*
 * Coupure d'Aura par la voix de l'utilisateur en mode vocal (« barge-in », TalkSession.kt) : regles pures, testees en JVM
 * (BargeInLogicTest). Le micro reste ouvert pendant qu'Aura repond ; le Vad passe alors en mode BARGE (seuil releve).
 *
 * Gardes anti-echo, de la plus large a la plus fine :
 * - source VOICE_COMMUNICATION + AcousticEchoCanceler + NoiseSuppressor quand le telephone les a (MicRecorder, TalkAudio.kt) ;
 * - seuil BARGE : bruit + 18 dB, plancher -38 dBFS (Vad) ;
 * - voix soutenue MIN_VOICE_MS avant de couper (250 ms jusqu'au 17/10 : une syllabe d'echo suffisait presque) ;
 * - grace GRACE_MS au debut de chaque lecture : l'annulation d'echo converge sur les premieres centaines de ms
 *   (remise a zero a chaque nouvelle ecoute et a chaque envoi : elle ne deborde pas sur la reponse suivante) ;
 * - reglage « Micro coupe pendant qu'Aura parle » (endroits bruyants) : plus aucune detection tant qu'Aura repond,
 *   on la coupe en touchant l'orbe.
 */
object BargeInLogic {
  const val MIN_VOICE_MS = 360
  const val GRACE_MS = 600
  /** Pref du reglage (meme cle que setPref cote JS : prefixe js_ ajoute par le module). */
  const val PREF_MUTE = "js_talk_mute_speaking"

  /** Etats ou Aura « repond » : envoi, reflexion, lecture. L'utilisateur qui parle ici = coupure (si permise). */
  val BUSY = setOf("sending", "thinking", "speaking")

  fun busy(state: String): Boolean = state in BUSY

  /** Mode du Vad pour cet etat de session. */
  fun modeFor(state: String, muteWhileSpeaking: Boolean): Vad.Mode = when {
    !busy(state) -> Vad.Mode.LISTEN
    muteWhileSpeaking -> Vad.Mode.MUTED
    else -> Vad.Mode.BARGE
  }

  /**
   * Derniere barriere dans onSpeechStart : couper Aura ? Jamais si le micro est coupe pendant qu'elle parle, ni pendant la
   * grace du debut de lecture (`sincePlaybackMs` < 0 = pas de lecture en cours).
   */
  fun shouldInterrupt(state: String, muteWhileSpeaking: Boolean, sincePlaybackMs: Long): Boolean {
    if (!busy(state) || muteWhileSpeaking) return false
    return !(state == "speaking" && sincePlaybackMs in 0 until GRACE_MS)
  }

  /** Valeur de la pref (« 1 » = micro coupe) ; absente ou autre = coupure a la voix permise, comme avant. */
  fun muted(pref: String?): Boolean = pref == "1"
}
