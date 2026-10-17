package dev.aura.mobile.protocol

/*
 * Garde de l'extra `record` de MainActivity (revue securite du 17/10, montre n°1). MainActivity est exportee (LAUNCHER) :
 * n'importe quelle app de la montre pouvait lancer `putExtra("record", true)` et ouvrir le micro sans geste. Seule la tuile
 * « Parler » doit demarrer un enregistrement : elle joint a son intent un jeton secret (`record_token`) que MainActivity
 * verifie avant startRecording, comme TileClicks pour la pause. Le jeton est aleatoire (128 bits), cree a la premiere
 * utilisation et garde dans les prefs privees de l'app : il vit dans la mise en page de la tuile, que seul l'hote systeme
 * lit, et survit au processus (la tuile affichee peut etre plus ancienne que le processus). Logique pure, testee en JVM
 * (RecordGateTest) ; la colle Android est RecordTokenStore.
 */
class RecordGate(
  private val read: () -> String?,
  private val write: (String) -> Unit,
  private val random: () -> String,
) {
  /** Jeton a mettre dans l'intent de la tuile (cree et enregistre s'il n'existe pas encore). */
  @Synchronized fun token(): String {
    val cur = read()
    if (cur != null && cur.length >= MIN_LEN) return cur
    return random().also(write)
  }

  /** L'intent recu autorise-t-il un enregistrement sans geste ? Faux sans jeton, avec un mauvais jeton, ou sans jeton emis. */
  @Synchronized fun allows(candidate: String?): Boolean {
    val cur = read()
    if (candidate == null || cur == null || cur.length < MIN_LEN) return false
    return constantTimeEquals(candidate, cur)
  }

  private fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var d = 0
    for (i in a.indices) d = d or (a[i].code xor b[i].code)
    return d == 0
  }

  companion object {
    const val MIN_LEN = 32
  }
}
