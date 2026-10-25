// Test de build-config.js (verification des URL de build) sans Expo ni jest.
//   node scripts/check-build-config.cjs
const assert = require("node:assert/strict")

const { checkUrls, assertUrls, insecureDevAllowed } = require("../build-config.js")

const B = "wss://h.example/ws"
const P = "https://h.example/app/"
assert.deepEqual(checkUrls(B, P, {}), [], "wss + https acceptes")
assert.equal(checkUrls("ws://h.example/ws", P, {}).length, 1, "ws:// refuse")
assert.equal(checkUrls(B, "http://h.example/", {}).length, 1, "http:// refuse")
assert.equal(checkUrls("ws://h", "http://h", { NODE_ENV: "production" }).length, 2, "les deux refuses en release")
assert.deepEqual(checkUrls("ws://10.0.2.2:8080/ws", "http://10.0.2.2:8080/", { AURA_ALLOW_INSECURE_DEV: "1" }), [], "chemin dev explicite")
assert.equal(checkUrls("ws://h", "http://h", { AURA_ALLOW_INSECURE_DEV: "1", NODE_ENV: "production" }).length, 2, "dev ignore en production")
assert.equal(insecureDevAllowed({ AURA_ALLOW_INSECURE_DEV: "true" }), false, "seule la valeur 1 compte")
for (const bad of ["", "wss://", "wss:// h/ws", "https://h/ws", "wss:h", "ftp://h", "wss://h\nx"]) {
  assert.equal(checkUrls(bad, P, {}).length, 1, "bridge refuse : " + JSON.stringify(bad))
}
for (const bad of ["", "https://", "https:// h/", "http://h.example/", "wss://h.example/", "https:h", "ftp://h", "https://h\nx", "javascript:alert(1)", "//h.example/"]) {
  assert.equal(checkUrls(B, bad, {}).length, 1, "pwa refusee : " + JSON.stringify(bad))
  assert.throws(() => assertUrls(B, bad, {}), /AURA_PWA_URL/, "assertUrls nomme la variable : " + JSON.stringify(bad))
}
assert.equal(checkUrls(B, "http://h.example/", { AURA_ALLOW_INSECURE_DEV: "1" }).length, 0, "http accepte seulement en dev explicite")
assert.equal(checkUrls(B, "ftp://h.example/", { AURA_ALLOW_INSECURE_DEV: "1" }).length, 1, "meme en dev, ftp refuse")
assert.throws(() => assertUrls(B, "http://secret.example/", {}), (e) => !String(e.message).includes("secret.example"), "l'erreur ne cite pas l'URL de la PWA")
assert.throws(() => assertUrls("ws://secret.example", P, {}), (e) => !String(e.message).includes("secret.example"), "l'erreur ne cite pas l'URL")
assert.doesNotThrow(() => assertUrls(B, P, {}))
console.log("build-config : OK")
