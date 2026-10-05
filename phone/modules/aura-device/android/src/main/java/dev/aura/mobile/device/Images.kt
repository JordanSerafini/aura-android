package dev.aura.mobile.device

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Images envoyees au bridge : JPEG (seul format accepte, PROTOCOL.md §7.2), 1280 px max. */
object Images {
  const val MAX_SIDE = 1280

  class Jpeg(val bytes: ByteArray, val width: Int, val height: Int) {
    fun base64(): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
  }

  fun scale(src: Bitmap, maxSide: Int = MAX_SIDE, rotate: Int = 0, mirror: Boolean = false): Bitmap {
    val ratio = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
    if (ratio >= 1f && rotate == 0 && !mirror) return src
    val m = Matrix()
    m.postScale(ratio * (if (mirror) -1f else 1f), ratio)
    if (rotate != 0) m.postRotate(rotate.toFloat())
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
  }

  fun jpeg(src: Bitmap, quality: Int = 75, maxSide: Int = MAX_SIDE, rotate: Int = 0, mirror: Boolean = false): Jpeg {
    // capture d'ecran : Bitmap HARDWARE non lisible par compress() sur certaines versions
    val readable = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
    val b = scale(readable, maxSide, rotate, mirror)
    val out = ByteArrayOutputStream()
    b.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return Jpeg(out.toByteArray(), b.width, b.height)
  }

  /** Image partagee (content://) : decodee sous-echantillonnee (une photo de 50 Mpx ne tient pas en RAM). */
  fun fromUri(ctx: Context, uri: Uri, quality: Int = 80): Jpeg? {
    val cr = ctx.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    val rotation = try {
      cr.openInputStream(uri)?.use { exifRotation(it) } ?: 0
    } catch (e: Exception) { 0 }
    return jpeg(bmp, quality, MAX_SIDE, rotation)
  }

  private fun exifRotation(input: java.io.InputStream): Int {
    val exif = android.media.ExifInterface(input)
    return when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
      android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
      android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
      android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
      else -> 0
    }
  }
}
