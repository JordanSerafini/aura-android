package dev.aura.mobile.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeLogicTest {
  private val max = 15

  @Test
  fun louderRaisesVolumeBelowMax() {
    assertEquals(VolumeAction.Raise, VolumeLogic.decide(7, max, 0, VolumeKey.LOUDER))
    // boost mémorisé mais volume baissé hors de l'app : on remonte d'abord le volume
    assertEquals(VolumeAction.Raise, VolumeLogic.decide(14, max, 2, VolumeKey.LOUDER))
  }

  @Test
  fun louderBoostsAtMaxUpToLimit() {
    assertEquals(VolumeAction.SetBoost(1), VolumeLogic.decide(max, max, 0, VolumeKey.LOUDER))
    assertEquals(VolumeAction.SetBoost(3), VolumeLogic.decide(max, max, 2, VolumeKey.LOUDER))
    assertEquals(VolumeAction.None, VolumeLogic.decide(max, max, VolumeLogic.BOOST_MAX, VolumeKey.LOUDER))
  }

  @Test
  fun quieterDropsBoostOnlyAtMax() {
    assertEquals(VolumeAction.SetBoost(1), VolumeLogic.decide(max, max, 2, VolumeKey.QUIETER))
    assertEquals(VolumeAction.Lower, VolumeLogic.decide(max, max, 0, VolumeKey.QUIETER))
    // sous le max, un boost resté en mémoire ne compte plus : « − » baisse vraiment le son
    assertEquals(VolumeAction.Lower, VolumeLogic.decide(10, max, 3, VolumeKey.QUIETER))
    assertEquals(VolumeAction.None, VolumeLogic.decide(0, max, 0, VolumeKey.QUIETER))
  }

  @Test
  fun boostOnlyCountsAtMax() {
    assertEquals(0, VolumeLogic.effectiveBoost(14, max, 3))
    assertEquals(2, VolumeLogic.effectiveBoost(max, max, 2))
    assertEquals(3, VolumeLogic.effectiveBoost(max, max, 9))
  }

  @Test
  fun labelDoesNotLieBelowMax() {
    assertEquals("Volume 7/15", VolumeLogic.label(VolumeLevel(7, max, 2)))
    assertEquals("Volume max + boost 2/3", VolumeLogic.label(VolumeLevel(max, max, 2)))
    assertEquals("Volume 15/15", VolumeLogic.label(VolumeLevel(max, max, 0)))
    assertEquals("Son coupé", VolumeLogic.label(VolumeLevel(0, max, 0)))
  }
}
