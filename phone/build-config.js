// Verification des URL fournies au build (revue securite du 17/10) : le jeton appareil part dans le `hello` du WebSocket et
// dans le fragment #pair= de la PWA ; une faute de frappe (ws://, http://) l'enverrait en clair. Fonctions pures, appelees
// par app.config.ts ; testees par `npm run test:config` (scripts/check-build-config.cjs).
// En JavaScript (et non en TypeScript) parce que le chargeur de config d'Expo ne transpile que app.config.ts lui-meme :
// un `import "./build-config"` vers un .ts echoue (« Cannot find module »). Les types sont dans build-config.d.ts.
//
// Regle : wss:// et https:// obligatoires. Chemin de dev EXPLICITE : AURA_ALLOW_INSECURE_DEV=1 autorise ws:// et http://
// (serveur local), jamais quand NODE_ENV=production (scripts/build.sh, build release). Cote natif, la meme regle s'applique
// a l'execution (BridgeUrl.kt : ws:// n'est accepte que dans un APK debuggable).

const WSS = /^wss:\/\/[^/?#\s]+(?:[/?#]\S*)?$/i
const WS = /^wss?:\/\/[^/?#\s]+(?:[/?#]\S*)?$/i
const HTTPS = /^https:\/\/[^/?#\s]+(?:[/?#]\S*)?$/i
const HTTP = /^https?:\/\/[^/?#\s]+(?:[/?#]\S*)?$/i

function insecureDevAllowed(env) {
  return env.AURA_ALLOW_INSECURE_DEV === "1" && env.NODE_ENV !== "production"
}

/** Messages d'erreur (liste vide = URL acceptees). N'inclut jamais la valeur de l'URL (peut designer un hote prive). */
function checkUrls(bridgeUrl, pwaUrl, env) {
  const dev = insecureDevAllowed(env)
  const errors = []
  if (!(dev ? WS : WSS).test(bridgeUrl)) {
    errors.push("AURA_BRIDGE_URL doit commencer par wss:// (ws:// seulement avec AURA_ALLOW_INSECURE_DEV=1, hors NODE_ENV=production)")
  }
  if (!(dev ? HTTP : HTTPS).test(pwaUrl)) {
    errors.push("AURA_PWA_URL doit commencer par https:// (http:// seulement avec AURA_ALLOW_INSECURE_DEV=1, hors NODE_ENV=production)")
  }
  return errors
}

/** Leve une Error si une URL est refusee : le build s'arrete avant de generer quoi que ce soit. */
function assertUrls(bridgeUrl, pwaUrl, env) {
  const errors = checkUrls(bridgeUrl, pwaUrl, env)
  if (errors.length > 0) throw new Error("Configuration de build refusee : " + errors.join(" ; "))
}

module.exports = { insecureDevAllowed, checkUrls, assertUrls }
