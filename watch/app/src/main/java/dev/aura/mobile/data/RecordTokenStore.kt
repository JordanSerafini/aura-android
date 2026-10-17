package dev.aura.mobile.data

import android.content.Context
import dev.aura.mobile.protocol.RecordGate
import java.security.SecureRandom

/** Colle Android de RecordGate : le jeton vit dans des SharedPreferences privees (jamais sauvegardees : allowBackup=false). */
object RecordTokenStore {
  private const val PREFS = "aura_record_gate"
  private const val KEY = "token"
  private val rng = SecureRandom()

  fun gate(context: Context): RecordGate {
    val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return RecordGate(
      read = { prefs.getString(KEY, null) },
      write = { prefs.edit().putString(KEY, it).apply() },
      random = { ByteArray(16).also(rng::nextBytes).joinToString("") { "%02x".format(it) } },
    )
  }
}
