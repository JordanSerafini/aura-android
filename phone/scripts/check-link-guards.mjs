// Test des gardes de liens (src/linkGuards.ts) sans Expo ni jest : node scripts/check-link-guards.mjs (npm run test:guards).
// Le .ts est transpile par le `typescript` du projet (certains Node n'effacent pas les types), puis importe en memoire.
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { createRequire } from "node:module"

const ts = createRequire(import.meta.url)("typescript")
const src = readFileSync(new URL("../src/linkGuards.ts", import.meta.url), "utf8")
const js = ts.transpileModule(src, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 } }).outputText
const { autoStartNonce, hashOf, isPwaUrlOf, splitUrl } = await import("data:text/javascript;base64," + Buffer.from(js).toString("base64"))

const O = "https://aura.example.org"
// isPwaUrl : origine EGALE
for (const ok of [O, O + "/", O + "/app/?x=1#y", "HTTPS://AURA.EXAMPLE.ORG/x"]) assert.equal(isPwaUrlOf(ok, O), true, "PWA : " + ok)
for (const bad of [
  "https://aura.example.org.evil.tld/", "https://aura.example.org@evil.tld/", "https://evil.tld/https://aura.example.org",
  "http://aura.example.org/", "https://aura.example.orgx/", "https://aura.example.org:8443/", "//aura.example.org/", "javascript:alert(1)",
  "", "aura://talk", "data:text/html,x", " https://aura.example.org/",
]) assert.equal(isPwaUrlOf(bad, O), false, "pas la PWA : " + JSON.stringify(bad))
assert.deepEqual(splitUrl("https://h.example/a/b?q#f"), { origin: "https://h.example", path: "/a/b" })
assert.deepEqual(splitUrl("https://h.example"), { origin: "https://h.example", path: "/" })
assert.equal(splitUrl("ftp://h.example/"), null)

// autostart du micro : jamais sans jeton de 32 hex minuscules
const N = "0123456789abcdef0123456789abcdef"
assert.equal(autoStartNonce(`aura://talk?autostart=1&n=${N}`), N)
assert.equal(autoStartNonce(`aura://talk?x=2&autostart=1&n=${N}`), N)
assert.equal(autoStartNonce(`aura://talk?n=${N}&autostart=1`), N, "n avant autostart")
for (const bad of [
  null, "", "aura://talk", "aura://talk?autostart=1", "aura://talk?autostart=0&n=" + N, "aura://talk?autostart=1&n=",
  "aura://talk?autostart=1&n=" + N.slice(1), "aura://talk?autostart=1&n=" + N.toUpperCase(), "aura://talk?autostart=1&n=zz" + N.slice(2),
  "aura://conv/abc?autostart=1&n=" + N, "https://x.example/talk?autostart=1&n=" + N, "aura://talkx?autostart=1&n=" + N,
  "aura://talk?autostart=10&n=" + N,
]) assert.equal(autoStartNonce(bad), null, "pas d'autostart : " + JSON.stringify(bad))
// F1 (revue securite du 27/10) : seul un bouton de notification scelle par le telephone devient #notif-act / #notif-ask
const K = "?k=0123456789abcdef0123456789abcdef"
const sealed = (u) => u.endsWith(K)
assert.equal(hashOf("aura://notif/7ad617618602/act" + K, sealed), "notif-act=7ad617618602")
assert.equal(hashOf("aura://notif/7ad617618602/ask" + K, sealed), "notif-ask=7ad617618602")
for (const forged of ["aura://notif/7ad617618602/act", "aura://notif/7ad617618602/act?k=ffffffffffffffffffffffffffffffff", "aura://notif/x/ask"]) {
  assert.equal(hashOf(forged, sealed), "notifs", "lien d'une autre app : la liste seulement : " + forged)
}
assert.equal(hashOf("aura://notif/7ad617618602/act" + K, undefined), "notifs")
assert.equal(hashOf("aura://conv/abc_12", () => false), "conv=abc_12")
assert.equal(hashOf("aura://notifs", () => false), "notifs")
assert.equal(hashOf("aura://talk", () => false), null)
console.log("link-guards : OK")
