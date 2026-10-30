package dev.aura.mobile.device

import java.util.concurrent.ConcurrentHashMap

/**
 * Demandes en cours indexees par l'`action_id` que fournit le bridge (revue securite du 27/10, N4). Un id deja pris est
 * REFUSE, jamais ecrase : sinon « Accepter » sur la carte d'une demande A validait la demande B que le bridge avait
 * envoyee sous le meme id. Logique pure, testee en JVM (IdTableTest).
 */
class IdTable<T : Any> {
  private val map = ConcurrentHashMap<String, T>()

  /** Pose `value` sous `id` ; false si l'id est deja pris (la demande en place ne bouge pas). */
  fun claim(id: String, value: T): Boolean = map.putIfAbsent(id, value) == null

  /** Retire l'entree seulement si c'est encore `value` : l'echeance tardive de A ne retire pas la demande suivante. */
  fun release(id: String, value: T): Boolean = map.remove(id, value)

  /** Retire et rend l'entree de `id`, quelle qu'elle soit. */
  fun take(id: String): T? = map.remove(id)

  /** Retire et rend l'entree de `id` seulement si `match` l'accepte (la reponse vient bien de SA carte). */
  fun takeIf(id: String, match: (T) -> Boolean): T? {
    val v = map[id] ?: return null
    return if (match(v) && map.remove(id, v)) v else null
  }

  operator fun get(id: String): T? = map[id]

  val size: Int get() = map.size

  fun values(): Collection<T> = map.values
}
