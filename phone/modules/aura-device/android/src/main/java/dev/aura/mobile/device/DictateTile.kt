package dev.aura.mobile.device

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Tuile Réglages rapides « Dicter à Aura » : un tap ouvre directement la conversation vocale mains libres de l'app (le même
 * écran que le bouton 🎧 et le raccourci « Parler » de l'icône : TalkScreen), qui démarre l'écoute toute seule
 * (`aura://talk?autostart=1`). Téléphone verrouillé, Android demande d'abord le déverrouillage (`unlockAndRun`), la tuile ne
 * contourne pas l'écran de verrouillage. Aucune permission en plus ; le micro est demandé par l'écran Talk s'il manque.
 *
 * Ajout par l'utilisateur : tirer le panneau des Réglages rapides complet, crayon (Modifier), glisser « Dicter à Aura » dans la grille.
 */
class DictateTileService : TileService() {
  override fun onStartListening() {
    qsTile?.let {
      it.state = Tile.STATE_INACTIVE  // une action, pas un interrupteur : jamais « allumée »
      it.label = "Dicter à Aura"
      if (Build.VERSION.SDK_INT >= 29) it.subtitle = "Mains libres"
      it.updateTile()
    }
  }

  override fun onClick() {
    Aura.init(this)
    Usage.count("tuile.dicter")
    if (isLocked) unlockAndRun { launch() } else launch()
  }

  private fun launch() {
    // MainActivity est ciblee par le package + le lien : meme chemin que le raccourci de l'icone (App.tsx : wantsTalk)
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TALK_URI)).setPackage(packageName)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    try {
      if (Build.VERSION.SDK_INT >= 34) {
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
      } else {
        @Suppress("DEPRECATION") startActivityAndCollapse(intent)
      }
    } catch (e: Exception) {
      Log.w(Aura.TAG, "tuile Dicter : app non ouverte", e)
    }
  }

  companion object {
    const val TALK_URI = "aura://talk?autostart=1"
  }
}
