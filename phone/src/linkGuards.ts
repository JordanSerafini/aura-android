// Gardes de liens SANS import React Native ni module natif : testables par `node scripts/check-link-guards.mjs`
// (npm run test:guards). AuraScreen.tsx et App.tsx les appellent ; ne pas y remettre une logique de garde en double.

// Origine et chemin d'une URL http(s), SANS `URL` : sous Hermes le polyfill de `URL` n'implemente pas tous les accesseurs
// (`pathname`, `origin` levent « not implemented » selon la version de React Native).
export function splitUrl(url: string): { origin: string, path: string } | null {
  const m = /^(https?:\/\/[^/?#]+)([^?#]*)/i.exec(url)
  return m ? { origin: m[1], path: m[2] || "/" } : null
}

// URL servie par la PWA : origine EGALE, pas un prefixe. Avec `startsWith(origin)`, https://hote.evil.tld ou
// https://hote@evil.tld passaient pour la PWA et se chargeaient dans la WebView (ponts natifs injectes, micro accorde
// sans invite par mediaCapturePermissionGrantType="grant").
export function isPwaUrlOf(url: string, pwaOrigin: string): boolean {
  return splitUrl(url)?.origin.toLowerCase() === pwaOrigin.toLowerCase()
}

// Jeton d'un lien `aura://talk?autostart=1&n=<32 hex>` : null si le lien ne demande pas le demarrage automatique du micro
// ou si le jeton est absent ou mal forme. Que ce soit VALIDE (usage unique, 30 s) se decide cote natif, apres cet appel.
export function autoStartNonce(url: string | null): string | null {
  if (!url || !/^aura:\/\/talk\?(?:.*&)?autostart=1\b/.test(url)) return null
  return /[?&]n=([0-9a-f]{32})\b/.exec(url)?.[1] ?? null
}
