package dev.aura.mobile.ui

import dev.aura.mobile.protocol.States

/**
 * Halo du micro (0.2.0) : animé seulement quand Aura écoute (enregistrement), réfléchit (transcription, réflexion) ou
 * parle (lecture) ; statique au repos ; l'animation continue coûtait de la batterie. « Supprimer les animations »
 * (échelle d'animation système à 0) le rend statique en toutes circonstances. Pas de mode ambiant dans l'app : quand
 * l'écran s'atténue, le système affiche le cadran et rien n'est dessiné.
 */
object HaloLogic {
  fun animated(recording: Boolean, state: String?, playing: Boolean, animationsEnabled: Boolean): Boolean =
    animationsEnabled && (recording || playing || state == States.THINKING || state == States.TRANSCRIBING)
}
