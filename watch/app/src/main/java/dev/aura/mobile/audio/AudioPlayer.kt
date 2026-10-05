package dev.aura.mobile.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.PowerManager
import android.util.Log
import dev.aura.mobile.data.AuraBus
import java.io.File

/**
 * Lecture des MP3 reçus sur le haut-parleur de la montre (un seul à la fois).
 *
 * Volume (28/09, « j'arrive pas à gérer mon volume ») : la lecture passe sur le flux MÉDIA, celui que règlent la
 * lunette et le panneau rapide de la montre (en USAGE_ASSISTANT, elle suivait un volume que l'utilisateur ne trouvait nulle
 * part). Les boutons − / + de l'app règlent ce flux ; au maximum, « + » ajoute un boost logiciel (LoudnessEnhancer,
 * +4 dB par cran, 3 crans) pour le petit haut-parleur. Le boost est mémorisé, mais ne vaut qu'au volume max
 * (décisions dans [VolumeLogic]) : sous le max, il est remis à 0.
 *
 * Focus audio : demandé en GAIN_TRANSIENT_MAY_DUCK à chaque lecture, rendu à la fin ; perdu (appel...) = arrêt.
 */
object AudioPlayer {
  private const val TAG = "AuraPlayer"
  private const val PREFS = "aura_audio"
  private const val BOOST_STEP_MB = 400
  const val BOOST_MAX = VolumeLogic.BOOST_MAX
  private val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()
  private var player: MediaPlayer? = null
  private var enhancer: LoudnessEnhancer? = null
  private var focus: AudioFocusRequest? = null
  private var focusManager: AudioManager? = null

  @Synchronized
  fun play(context: Context, file: File): Boolean {
    stop()
    if (!file.exists()) return false
    val app = context.applicationContext
    // Hors du try : si prepare()/start() échoue, ce lecteur-là doit être libéré (player n'est pas encore assigné).
    var mp: MediaPlayer? = null
    return try {
      val p = MediaPlayer()
      mp = p
      p.setAudioAttributes(ATTRIBUTES)
      speaker(app)?.let { p.setPreferredDevice(it) }
      p.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
      p.setDataSource(file.absolutePath)
      p.setOnCompletionListener { release(it) }
      p.setOnErrorListener { e, what, extra ->
        Log.w(TAG, "erreur lecture $what/$extra")
        release(e)
        true
      }
      p.prepare()
      check(requestFocus(app)) { "focus audio refusé" }
      applyBoost(p, currentBoost(app))
      p.start()
      player = p
      AuraBus.playing.value = true
      true
    } catch (e: Exception) {
      Log.e(TAG, "play", e)
      enhancer?.release()
      enhancer = null
      mp?.release()
      abandonFocus()
      AuraBus.playing.value = player != null
      false
    }
  }

  /** « + » : le volume média de la montre, puis le boost une fois au maximum. Rend true si le niveau a changé. */
  @Synchronized
  fun louder(context: Context): Boolean = press(context, VolumeKey.LOUDER)

  /** « − » : le boost d'abord (au max seulement), puis le volume média. Rend true si le niveau a changé. */
  @Synchronized
  fun quieter(context: Context): Boolean = press(context, VolumeKey.QUIETER)

  private fun press(context: Context, key: VolumeKey): Boolean {
    val app = context.applicationContext
    val am = app.getSystemService(AudioManager::class.java) ?: return false
    val before = publish(app)
    val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    when (val action = VolumeLogic.decide(v, max, boost(app), key)) {
      VolumeAction.Raise -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
      VolumeAction.Lower -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
      is VolumeAction.SetBoost -> setBoost(app, action.level)
      VolumeAction.None -> Unit
    }
    return publish(app) != before
  }

  /**
   * Relit le volume média et publie le niveau (écran principal, écran volume). Appelé aussi quand le volume
   * change hors de l'app (lunette système, panneau rapide) : sous le max, le boost mémorisé est remis à 0.
   */
  @Synchronized
  fun publish(context: Context): VolumeLevel? {
    val app = context.applicationContext
    val am = app.getSystemService(AudioManager::class.java) ?: return null
    val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    var b = boost(app)
    if (b > 0 && VolumeLogic.effectiveBoost(v, max, b) == 0) {
      setBoost(app, 0)
      b = 0
    }
    return VolumeLevel(v, max, b).also { AuraBus.volume.value = it }
  }

  private fun currentBoost(context: Context): Int {
    val am = context.getSystemService(AudioManager::class.java) ?: return 0
    return VolumeLogic.effectiveBoost(am.getStreamVolume(AudioManager.STREAM_MUSIC), am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), boost(context))
  }

  private fun boost(context: Context): Int =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("boost", 0).coerceIn(0, BOOST_MAX)

  private fun setBoost(context: Context, value: Int) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("boost", value).apply()
    player?.let { applyBoost(it, value) }
  }

  private fun applyBoost(mp: MediaPlayer, level: Int) {
    enhancer?.release()
    enhancer = null
    if (level <= 0) return
    enhancer = runCatching {
      LoudnessEnhancer(mp.audioSessionId).apply {
        setTargetGain(level * BOOST_STEP_MB)
        enabled = true
      }
    }.onFailure { Log.w(TAG, "boost indisponible", it) }.getOrNull()
  }

  private fun requestFocus(context: Context): Boolean {
    val am = context.getSystemService(AudioManager::class.java) ?: return true
    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
      .setAudioAttributes(ATTRIBUTES)
      .setOnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
      }
      .build()
    if (am.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
    focus = request
    focusManager = am
    return true
  }

  private fun abandonFocus() {
    focus?.let { f -> focusManager?.let { runCatching { it.abandonAudioFocusRequest(f) } } }
    focus = null
    focusManager = null
  }

  @Synchronized
  fun stop() {
    enhancer?.release()
    enhancer = null
    player?.let {
      runCatching { it.stop() }
      it.release()
    }
    player = null
    abandonFocus()
    AuraBus.playing.value = false
  }

  @Synchronized
  private fun release(mp: MediaPlayer) {
    if (player === mp) {
      player = null
      enhancer?.release()
      enhancer = null
      abandonFocus()
    }
    mp.release()
    AuraBus.playing.value = player != null
  }

  /** Haut-parleur intégré de la montre (si disponible, sinon sortie par défaut). */
  private fun speaker(context: Context): AudioDeviceInfo? {
    val am = context.getSystemService(AudioManager::class.java) ?: return null
    return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
  }
}
