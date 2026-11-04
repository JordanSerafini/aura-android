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

// lien profond -> ancre de la PWA : aura://conv/<id> (partage, assistant) ; aura://view/<onglet> (raccourcis de l'icone) ;
// aura://notifs et aura://notif/<id>/act|ask
// (boutons des notifications Android d'Aura, Notifs.kt). Revue securite du 27/10 (F1) : le schema aura:// s'ouvre depuis
// n'importe quelle app ou page web, et `#notif-act` peut EXECUTER l'action de la notification dans la PWA. Seul un lien
// scelle par le telephone (NotifLink.kt, `?k=`) est relaye ; sinon la liste des notifications, sans rien executer.
export function hashOf(url: string | null, sealed?: (url: string) => boolean): string | null {
  if (!url) return null
  const conv = /^aura:\/\/conv\/([A-Za-z0-9_-]{1,64})/.exec(url)
  if (conv) return `conv=${conv[1]}`
  const notif = /^aura:\/\/notif\/([A-Za-z0-9_-]{1,64})\/(act|ask)\b/.exec(url)
  if (notif) return sealed?.(url) ? `notif-${notif[2]}=${notif[1]}` : "notifs"
  // raccourcis de l'icone (appui long) : aura://view/<onglet> -> l'ancre que la barre du bas utilise deja. Liste fermee :
  // un autre nom ne devient jamais une ancre de la PWA.
  const view = /^aura:\/\/view\/(chat|notifs|actions|backlog|rappels)\b/.exec(url)
  if (view) return `view=${view[1]}`
  return /^aura:\/\/notifs\b/.test(url) ? "notifs" : null
}

// F2 (revue securite du 27/10) : au 1er lancement la WebView charge `PWA_URL#pair=<jeton>&name=…`. Une redirection hors
// origine SANS fragment dans `Location` herite de ce fragment (RFC 9110 §10.2.2) : le lien externe ouvert dans le
// navigateur emporterait le jeton de l'appareil. Un fragment qui porte `pair=` est retire ; les autres ancres restent.
export function externalUrl(url: string): string {
  return /#.*\bpair=/i.test(url) ? url.replace(/#.*$/, "") : url
}
