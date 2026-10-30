package dev.aura.mobile.device

/**
 * clipboard_set (revue securite du 27/10, N8) : un texte de paiement, celui qu'on colle dans un virement (IBAN, carte,
 * adresse de portefeuille), se fait confirmer sur le telephone : le remplacer en douce est le piege du presse-papiers.
 * Le reste (une adresse, un code de portail, un lien envoyes depuis un PC, telephone en poche) passe sans carte : une
 * confirmation systematique ferait echouer ces envois au bout du delai. Logique pure, testee en JVM (ClipboardGuardTest).
 */
object ClipGuard {
  /** Longueur maximale d'un texte copie (meme borne que le serveur). */
  const val CLIP_MAX = 2000

  private val IBAN = Regex("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}\\b")
  // Ethereum (0x + 40 hex), Bitcoin bech32 (bc1…) et base58 (1… / 3…)
  private val CRYPTO = Regex("\\b(?:0x[0-9a-fA-F]{40}|bc1[ac-hj-np-z02-9]{11,71}|[13][a-km-zA-HJ-NP-Z1-9]{25,34})\\b")

  private fun luhn(d: String): Boolean {
    var sum = 0
    d.reversed().forEachIndexed { i, ch ->
      var n = ch - '0'
      if (i % 2 == 1) { n *= 2; if (n > 9) n -= 9 }
      sum += n
    }
    return sum % 10 == 0
  }

  /** Carte bancaire : 4 groupes de 4 chiffres, ou 13 a 19 chiffres au Luhn valide. */
  private fun card(t: String): Boolean = Regex("(?<!\\d)\\d{4}([ -])\\d{4}\\1\\d{4}\\1\\d{4}(?!\\d)").containsMatchIn(t) ||
    Regex("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)").findAll(t).any { m ->
      val d = m.value.filter { it.isDigit() }
      d.length in 13..19 && luhn(d) && !d.startsWith("0")
    }

  private fun ibanLike(t: String): Boolean = IBAN.findAll(t).any { m -> m.value.count { it.isDigit() } >= 12 }

  fun payment(t: String): Boolean = ibanLike(t) || card(t) || CRYPTO.containsMatchIn(t)

  /**
   * Les coordonnees de paiement trouvees dans le texte, telles quelles : la carte de confirmation les montre EN ENTIER,
   * meme placees en fin d'un long texte que le resume tronque a 120 caracteres.
   */
  fun paymentParts(t: String): List<String> {
    val iban = IBAN.findAll(t).map { it.value }.filter { v -> v.count { it.isDigit() } >= 12 }
    val cards = Regex("(?<!\\d)\\d{4}([ -])\\d{4}\\1\\d{4}\\1\\d{4}(?!\\d)").findAll(t).map { it.value } +
      Regex("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)").findAll(t).map { it.value }.filter { v ->
        val d = v.filter { it.isDigit() }; d.length in 13..19 && luhn(d) && !d.startsWith("0") }
    val all = (iban + cards + CRYPTO.findAll(t).map { it.value }).map { it.trim() }.distinct().toList()
    // les chiffres d'un IBAN ou d'une adresse 0x… ressemblent a une carte : un morceau d'une coordonnee n'en est pas une 2e
    return all.filter { p -> all.none { it != p && it.contains(p) } }
  }
}
