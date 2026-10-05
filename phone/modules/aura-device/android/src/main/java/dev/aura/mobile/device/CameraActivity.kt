package dev.aura.mobile.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * `camera_snap` (PROTOCOL.md §7.4). Android interdit la camera a une appli en arriere-plan : on ouvre
 * une activite plein ecran noire (allume l'ecran, passe au-dessus de l'ecran verrouille), qui prend la
 * photo en ~1 s (le temps que l'exposition automatique converge) et se ferme.
 * Lancement app fermee : exige « Afficher par-dessus les autres applis ».
 */
object CameraCapture {
  private const val TIMEOUT_S = 20L
  private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()

  fun available(): Boolean = Aura.app.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
    Perms.granted(Manifest.permission.CAMERA)

  /** Bloquant : {image (JPEG base64), width, height, facing}. */
  fun snap(facing: String): JSONObject {
    if (!Aura.app.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
      throw ActionError("unsupported", "Pas de caméra sur cet appareil")
    }
    Perms.requireRuntime("camera", Manifest.permission.CAMERA)
    if (!Aura.appForeground && !Perms.overlay()) {
      throw ActionError("permission:overlay", "« Afficher par-dessus les autres applis » requis pour la caméra app fermée")
    }
    val id = java.util.UUID.randomUUID().toString()
    val f = CompletableFuture<JSONObject>()
    pending[id] = f
    try {
      val i = Intent(Aura.app, CameraActivity::class.java)
        .putExtra("req", id)
        .putExtra("facing", if (facing == "front") "front" else "back")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
      Aura.app.startActivity(i)
      return try {
        f.get(TIMEOUT_S, TimeUnit.SECONDS)
      } catch (e: TimeoutException) {
        throw ActionError("timeout", "La caméra n'a pas répondu en $TIMEOUT_S s")
      } catch (e: java.util.concurrent.ExecutionException) {
        val c = e.cause
        throw if (c is ActionError) c else ActionError("failed", "Caméra : ${c?.message}")
      }
    } finally {
      pending.remove(id)
    }
  }

  fun complete(id: String, result: JSONObject) { pending[id]?.complete(result) }
  fun fail(id: String, code: String, message: String) { pending[id]?.completeExceptionally(ActionError(code, message)) }
}

class CameraActivity : Activity() {
  companion object {
    private const val AE_WARMUP_MS = 900L
  }

  private var reqId = ""
  private var thread: HandlerThread? = null
  private var handler: Handler? = null
  private var device: CameraDevice? = null
  private var session: CameraCaptureSession? = null
  private var reader: ImageReader? = null
  private var texture: SurfaceTexture? = null
  private var done = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Aura.init(this)
    reqId = intent.getStringExtra("req") ?: run { finish(); return }
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContentView(View(this).apply { setBackgroundColor(android.graphics.Color.BLACK) })
    val t = HandlerThread("aura-camera").apply { start() }
    thread = t
    handler = Handler(t.looper)
    handler?.post { open(intent.getStringExtra("facing") ?: "back") }
    handler?.postDelayed({ fail("timeout", "Photo non prise (délai)") }, 15_000)
  }

  @SuppressLint("MissingPermission")
  private fun open(facing: String) {
    val cm = getSystemService(CameraManager::class.java)
    val wanted = if (facing == "front") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
    val camId = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted }
      ?: cm.cameraIdList.firstOrNull()
      ?: return fail("unsupported", "Aucune caméra")
    val ch = cm.getCameraCharacteristics(camId)
    val sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG) ?: arrayOf(Size(1280, 960))
    // la plus petite taille qui garde au moins 1280 px de cote long (on redimensionne ensuite)
    val size = sizes.filter { maxOf(it.width, it.height) >= Images.MAX_SIDE }.minByOrNull { it.width * it.height }
      ?: sizes.maxByOrNull { it.width * it.height }!!
    val r = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
    reader = r
    r.setOnImageAvailableListener({ ir ->
      val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
      val bytes = try {
        val buf = img.planes[0].buffer
        ByteArray(buf.remaining()).also { buf.get(it) }
      } finally { img.close() }
      deliver(bytes, sensor, facing)
    }, handler)
    val tex = SurfaceTexture(false).apply { setDefaultBufferSize(640, 480) }
    texture = tex
    val preview = Surface(tex)
    try {
      cm.openCamera(camId, object : CameraDevice.StateCallback() {
        override fun onOpened(d: CameraDevice) {
          device = d
          @Suppress("DEPRECATION")
          d.createCaptureSession(listOf(preview, r.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
              session = s
              val rep = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
              }
              try { s.setRepeatingRequest(rep.build(), null, handler) } catch (e: Exception) { return fail("failed", "Aperçu : ${e.message}") }
              // exposition et mise au point automatiques : ~1 s, puis la vraie photo
              handler?.postDelayed({
                try {
                  val still = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(r.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.JPEG_QUALITY, 92.toByte())
                  }
                  s.capture(still.build(), null, handler)
                } catch (e: Exception) { fail("failed", "Capture : ${e.message}") }
              }, AE_WARMUP_MS)
            }
            override fun onConfigureFailed(s: CameraCaptureSession) = fail("failed", "Session caméra refusée")
          }, handler)
        }
        override fun onDisconnected(d: CameraDevice) = fail("unavailable", "Caméra déconnectée (utilisée par une autre appli ?)")
        override fun onError(d: CameraDevice, error: Int) = fail("unavailable", "Caméra indisponible (erreur $error)")
      }, handler)
    } catch (e: SecurityException) {
      fail("permission:camera", "Permission caméra refusée")
    } catch (e: Exception) {
      fail("unavailable", "Caméra : ${e.message}")
    }
  }

  private fun deliver(jpeg: ByteArray, sensor: Int, facing: String) {
    if (done) return
    try {
      val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return fail("failed", "Photo illisible")
      // telephone suppose en portrait (poche, table) : rotation = orientation du capteur
      val out = Images.jpeg(bmp, 80, Images.MAX_SIDE, rotate = sensor % 360)
      done = true
      CameraCapture.complete(reqId, JSONObject().put("image", out.base64()).put("width", out.width)
        .put("height", out.height).put("facing", facing))
    } catch (e: Exception) {
      Log.w(Aura.TAG, "photo", e)
      fail("failed", "Photo : ${e.message}")
    }
    runOnUiThread { finish() }
  }

  private fun fail(code: String, message: String) {
    if (done) return
    done = true
    CameraCapture.fail(reqId, code, message)
    runOnUiThread { finish() }
  }

  override fun onDestroy() {
    if (!done) {
      done = true
      CameraCapture.fail(reqId, "failed", "Capture interrompue")
    }
    try { session?.close() } catch (e: Exception) { }
    try { device?.close() } catch (e: Exception) { }
    reader?.close()
    texture?.release()
    thread?.quitSafely()
    super.onDestroy()
  }
}
