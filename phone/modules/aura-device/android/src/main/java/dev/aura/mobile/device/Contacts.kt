package dev.aura.mobile.device

import android.Manifest
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** Resolution de contacts par nom (ContactsContract). */
object Contacts {
  data class Entry(val contactId: Long, val name: String, val number: String, val type: Int, val primary: Boolean)

  fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase()
    .replace(Regex("[^a-z0-9+ ]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

  fun looksLikeNumber(s: String): Boolean {
    val t = s.trim()
    return t.isNotEmpty() && t.none { it.isLetter() } && t.count { it.isDigit() } >= 3
  }

  fun cleanNumber(s: String): String = s.filter { it.isDigit() || it == '+' }

  fun search(q: String, limit: Int = 50): List<Entry> {
    Perms.requireRuntime("contacts", Manifest.permission.READ_CONTACTS)
    val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q.trim()))
    val out = mutableListOf<Entry>()
    Aura.app.contentResolver.query(uri, arrayOf(
      ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
      ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
      ContactsContract.CommonDataKinds.Phone.NUMBER,
      ContactsContract.CommonDataKinds.Phone.TYPE,
      ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
    ), null, null, null)?.use { c ->
      while (c.moveToNext() && out.size < limit) {
        out += Entry(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3), c.getInt(4) == 1)
      }
    }
    // un meme numero enregistre deux fois (formats differents) ne doit pas compter double
    return out.distinctBy { it.contactId to cleanNumber(it.number).takeLast(9) }
  }

  fun typeLabel(type: Int): String = when (type) {
    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
    ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "domicile"
    ContactsContract.CommonDataKinds.Phone.TYPE_WORK, ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> "travail"
    else -> "autre"
  }

  fun toJson(e: Entry): JSONObject = JSONObject().put("name", e.name).put("number", e.number).put("type", typeLabel(e.type))

  private fun best(entries: List<Entry>): Entry = entries.maxByOrNull {
    (if (it.primary) 4 else 0) + (if (it.type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) 2 else 0)
  }!!

  /** Numero (et nom) d'un destinataire donne en numero ou en nom. ActionError explicite si ambigu. */
  fun resolve(to: String): Pair<String, String?> {
    val t = to.trim()
    if (t.isEmpty()) throw ActionError("bad_params", "Destinataire vide")
    if (looksLikeNumber(t)) return cleanNumber(t) to nameFor(t)
    val q = norm(t)
    val found = search(t)
    if (found.isEmpty()) throw ActionError("contact_not_found", "Aucun contact ne correspond à « $t »")
    val byContact = found.groupBy { it.contactId }
    val exact = byContact.filterValues { list -> norm(list.first().name) == q }
    val chosen = when {
      exact.size == 1 -> exact.values.first()
      byContact.size == 1 -> byContact.values.first()
      else -> {
        // « Camille » : un seul contact dont un mot du nom vaut exactement la requete
        val word = byContact.filterValues { list -> norm(list.first().name).split(" ").contains(q) }
        if (word.size == 1) word.values.first() else null
      }
    }
    if (chosen == null) {
      val names = byContact.values.map { it.first().name }.distinct().take(8)
      val extra = JSONObject().put("candidates", JSONArray(byContact.values.take(8).map { toJson(best(it)) }))
      throw ActionError("ambiguous", "Plusieurs contacts correspondent à « $t » : ${names.joinToString(", ")}. Précise lequel.", extra)
    }
    val e = best(chosen)
    return cleanNumber(e.number) to e.name
  }

  /** Nom du contact d'un numero, null sans permission ou inconnu. */
  fun nameFor(number: String): String? {
    if (!Perms.granted(Manifest.permission.READ_CONTACTS) || number.isBlank()) return null
    return try {
      val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
      Aura.app.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
      }
    } catch (e: Exception) {
      null
    }
  }

  /** Numero au format international sans « + » (wa.me) : 06… -> 336…, 0033… -> 33… */
  fun international(number: String): String {
    var n = cleanNumber(number)
    if (n.startsWith("+")) return n.drop(1)
    if (n.startsWith("00")) return n.drop(2)
    if (n.startsWith("0") && n.length == 10) n = "33" + n.drop(1)
    return n
  }

  fun same(a: String, b: String): Boolean = PhoneNumberUtils.compare(a, b)
}
