package dev.aura.mobile.protocol

import dev.aura.mobile.protocol.ConfirmFlow.Closed
import dev.aura.mobile.protocol.ConfirmFlow.Expired
import dev.aura.mobile.protocol.ConfirmFlow.ExpiryRefusal
import dev.aura.mobile.protocol.ConfirmFlow.Failed
import dev.aura.mobile.protocol.ConfirmFlow.FailureAlert
import dev.aura.mobile.protocol.ConfirmFlow.LostNotice
import dev.aura.mobile.protocol.ConfirmFlow.Ready
import dev.aura.mobile.protocol.ConfirmFlow.Result
import dev.aura.mobile.protocol.ConfirmFlow.Send
import dev.aura.mobile.protocol.ConfirmFlow.Sending
import dev.aura.mobile.protocol.ConfirmFlow.SettledElsewhere
import dev.aura.mobile.protocol.ConfirmFlow.Tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmFlowTest {
  private fun run(vararg events: ConfirmFlow.Event): Pair<ConfirmFlow.State, List<ConfirmFlow.Effect>> {
    var state: ConfirmFlow.State = Ready
    val effects = mutableListOf<ConfirmFlow.Effect>()
    for (e in events) {
      val t = ConfirmFlow.next(state, e)
      state = t.state
      effects += t.effects
    }
    return state to effects
  }

  @Test fun accepter_attend_l_envoi_avant_de_fermer() {
    val t = ConfirmFlow.next(Ready, Tap(true))
    assertEquals(Sending(true), t.state)
    assertEquals(listOf(Send(true)), t.effects)
    assertFalse(ConfirmFlow.buttonsEnabled(t.state))  // boutons désactivés pendant l'envoi
    assertEquals(Closed, ConfirmFlow.next(t.state, Result(SendOutcome.SENT)).state)
  }

  @Test fun echec_garde_l_ecran_ouvert_vibre_et_reactive_les_boutons() {
    val (state, effects) = run(Tap(true), Result(SendOutcome.FAILED))
    assertEquals(Failed(1), state)
    assertEquals(listOf(Send(true), FailureAlert), effects)
    assertTrue(ConfirmFlow.buttonsEnabled(state))
  }

  @Test fun nouvelle_tentative_apres_echec_puis_succes() {
    val (state, effects) = run(Tap(true), Result(SendOutcome.FAILED), Tap(true), Result(SendOutcome.FAILED), Tap(false), Result(SendOutcome.SENT))
    assertEquals(Closed, state)
    assertEquals(listOf(Send(true), FailureAlert, Send(true), FailureAlert, Send(false)), effects)
  }

  @Test fun le_compte_des_echecs_monte() {
    val (state, _) = run(Tap(true), Result(SendOutcome.FAILED), Tap(true), Result(SendOutcome.FAILED))
    assertEquals(Failed(2), state)
  }

  @Test fun un_second_appui_pendant_l_envoi_est_ignore() {
    val t = ConfirmFlow.next(Sending(true), Tap(false))
    assertEquals(Sending(true), t.state)
    assertTrue(t.effects.isEmpty())
  }

  @Test fun expiration_sans_envoi_refuse_comme_avant() {
    assertEquals(ConfirmFlow.Transition(Closed, listOf(ExpiryRefusal)), ConfirmFlow.next(Ready, Expired))
    // après un échec aussi : le délai est fini, on ne réessaie plus
    assertEquals(ConfirmFlow.Transition(Closed, listOf(ExpiryRefusal)), ConfirmFlow.next(Failed(1), Expired))
  }

  @Test fun expiration_pendant_l_envoi_laisse_le_resultat_decider() {
    val (sent, sentEffects) = run(Tap(true), Expired, Result(SendOutcome.SENT))
    assertEquals(Closed, sent)
    assertEquals(listOf(Send(true)), sentEffects)  // pas de refus automatique par-dessus l'accord parti
    val (lost, lostEffects) = run(Tap(true), Expired, Result(SendOutcome.FAILED))
    assertEquals(Closed, lost)
    assertEquals(listOf(Send(true), LostNotice), lostEffects)  // l'écran se ferme, mais l'utilisateur le sait
  }

  @Test fun reglee_ailleurs_ferme_sans_rien_envoyer() {
    assertEquals(ConfirmFlow.Transition(Closed), ConfirmFlow.next(Ready, SettledElsewhere))
    assertEquals(ConfirmFlow.Transition(Closed), ConfirmFlow.next(Sending(true), SettledElsewhere))
    assertEquals(ConfirmFlow.Transition(Closed), ConfirmFlow.next(Failed(2), SettledElsewhere))
    assertEquals(Closed, ConfirmFlow.next(Sending(true), Result(SendOutcome.ALREADY_SETTLED)).state)
  }

  @Test fun ferme_reste_ferme() {
    for (e in listOf(Tap(true), Expired, SettledElsewhere, Result(SendOutcome.FAILED))) {
      assertEquals(ConfirmFlow.Transition(Closed), ConfirmFlow.next(Closed, e))
    }
  }

  @Test fun resultat_tardif_sans_envoi_en_cours_ne_change_rien() {
    assertEquals(ConfirmFlow.Transition(Ready), ConfirmFlow.next(Ready, Result(SendOutcome.FAILED)))
    assertEquals(ConfirmFlow.Transition(Failed(1)), ConfirmFlow.next(Failed(1), Result(SendOutcome.SENT)))
  }

  @Test fun libelles_du_haut_de_l_ecran() {
    assertEquals("Confirmer ? 30 s", ConfirmFlow.headline(Ready, 29_100))
    assertEquals("Envoi…", ConfirmFlow.headline(Sending(true), 12_000))
    assertEquals("Téléphone injoignable, réponds sur le téléphone · 12 s", ConfirmFlow.headline(Failed(1), 12_000))
    assertEquals("Confirmer ? 0 s", ConfirmFlow.headline(Ready, -500))
  }
}
