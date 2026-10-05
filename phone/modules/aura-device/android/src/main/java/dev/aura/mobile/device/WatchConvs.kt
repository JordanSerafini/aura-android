package dev.aura.mobile.device

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Conversations ouvertes du bridge (liste du `welcome`, puis diffusions `conv` et `conv_closed`) et celle que
 * l'utilisateur a choisie sur la montre (« Reprendre une conversation », 28/09). Sans choix, la montre parle dans la
 * plus recente, comme avant. Le choix survit a un redemarrage (prefs) et tombe si la conversation est fermee.
 */
object WatchConvs {
  private const val PREF = "watch_conv"
  private const val PUSH_GAP_MS = 1_000L
  private val lock = Any()
  private val convs = LinkedHashMap<String, JSONObject>()
  /** Messages de la montre retenus pendant qu'un onglet neuf se cree : envoi(conv ou null). */
  private val newWait = NewConvWait<(String?) -> Unit>()
  /** Derniere montre qui a parle ; `asked` : elle a deja demande la liste depuis le demarrage. */
  @Volatile private var node: String? = null
  @Volatile private var asked = false
  private var lastPush = 0L
  private var pushQueued = false

  fun heard(node: String, asked: Boolean = false) {
    this.node = node
    if (asked) this.asked = true
  }

  /** req absent (montre d'avant le 28/09) : on en genere un. */
  fun newReq(): String = "s22-" + java.lang.Long.toString(System.nanoTime() and 0xffffffffL, 36)

  /** La montre demande un onglet neuf : attente du `conv` portant ce req (15 s au plus). */
  fun requestNew(node: String, req: String) {
    newWait.start(req, node, System.currentTimeMillis())
    Aura.timer.schedule(Runnable { Aura.io.execute { expireNew() } }, NewConvWait.WAIT_MS + 50, TimeUnit.MILLISECONDS)
  }

  /** Message vocal/texte de la montre : retenu si un onglet neuf est attendu (true), sinon a envoyer tout de suite. */
  fun holdIfWaiting(send: (String?) -> Unit): Boolean = newWait.hold(send, System.currentTimeMillis())

  /** `conv` diffuse avec `req` : si c'est l'onglet demande par la montre, il devient son choix. */
  fun onCreated(req: String, id: String): Boolean {
    if (!newWait.matches(req, System.currentTimeMillis())) return false
    select(id)  // avant resolve : un message qui arrive entre les deux part deja dans le nouvel onglet
    val r = newWait.resolve(req, System.currentTimeMillis()) ?: return true
    markPushed()
    val p = payload().put("created", req)
    Aura.wearQ.execute { Aura.watch.send(r.node, "/aura/convs", p) }
    r.held.forEach { it(id) }
    return true
  }

  /** Onglet neuf non arrive a temps : les messages retenus partent sans `conv` (le bridge prend la plus recente). */
  private fun expireNew() {
    newWait.expire(System.currentTimeMillis())?.forEach { it(null) }
  }

  /** Demande d'onglet neuf non envoyee, ou WebSocket coupee : rien ne doit rester en attente. */
  fun cancelNew() {
    newWait.cancel().forEach { it(null) }
  }

  fun bridgeDown() = cancelNew()

  /** Liste a jour vers la derniere montre (au plus 1 envoi/s), seulement si une montre l'a deja demandee. */
  fun pushSoon() {
    val n = node ?: return
    if (!asked) return
    val delay = synchronized(lock) {
      if (pushQueued) return
      pushQueued = true
      coalesceDelay(lastPush, System.currentTimeMillis(), PUSH_GAP_MS)
    }
    Aura.timer.schedule(Runnable {
      markPushed()
      Aura.wearQ.execute { Aura.watch.send(n, "/aura/convs", payload()) }
    }, delay, TimeUnit.MILLISECONDS)
  }

  private fun markPushed() = synchronized(lock) {
    pushQueued = false
    lastPush = System.currentTimeMillis()
  }

  /** Reponse `/aura/convs` a une demande de la montre : `error: "offline"` si le bridge est injoignable. */
  fun reply(): JSONObject = payload().also { if (!Aura.bridge.online()) it.put("error", "offline") }

  fun reset(arr: JSONArray?) = synchronized(lock) {
    convs.clear()
    if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(::put)
  }

  fun upsert(conv: JSONObject?) {
    if (conv != null) synchronized(lock) { put(conv) }
  }

  fun remove(id: String?) {
    if (id == null) return
    synchronized(lock) { convs.remove(id) }
    if (Aura.prefs.getString(PREF, null) == id) select(null)
  }

  private fun put(c: JSONObject) {
    val id = c.optString("id")
    if (id.isEmpty()) return
    convs[id] = JSONObject()
      .put("id", id)
      .put("title", c.optString("title").ifBlank { "Nouvelle conversation" })
      .put("busy", c.optBoolean("busy"))
      .put("updated", c.optDouble("updated", 0.0))
  }

  /** Conversation choisie sur la montre, si elle est toujours ouverte (liste pas encore recue : on garde le choix). */
  fun selected(): String? {
    val id = Aura.prefs.getString(PREF, null) ?: return null
    return synchronized(lock) { if (convs.isEmpty() || convs.containsKey(id)) id else null }
  }

  fun select(id: String?) {
    Aura.prefs.edit().apply { if (id.isNullOrEmpty()) remove(PREF) else putString(PREF, id) }.apply()
  }

  /** `/aura/convs` : la plus recemment active d'abord ; `active` = le choix de la montre ou null (la plus recente). */
  fun payload(): JSONObject {
    val list = synchronized(lock) { convs.values.sortedByDescending { it.optDouble("updated", 0.0) } }
    return JSONObject().put("convs", JSONArray(list)).put("active", selected() ?: JSONObject.NULL)
  }
}
