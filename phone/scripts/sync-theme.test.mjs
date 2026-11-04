// Test de la charte UI (22/10, lot 19) : les trois couches (React Native, Kotlin, widgets XML) portent les MÊMES couleurs,
// générées depuis app.css ; les paires texte/fond tiennent 4,5:1 et les contrôles 3:1 ; l'ancienne charte a disparu.
// Usage : node --test scripts/sync-theme.test.mjs   (node 22, aucune dépendance)
import { test } from "node:test"
import assert from "node:assert/strict"
import { execFileSync } from "node:child_process"
import { readFileSync, readdirSync } from "node:fs"
import { dirname, resolve, join } from "node:path"
import { fileURLToPath } from "node:url"

const PHONE = resolve(dirname(fileURLToPath(import.meta.url)), "..")
const MAIN = join(PHONE, "modules/aura-device/android/src/main")
const KT_DIR = join(MAIN, "java/dev/aura/mobile/device")

function tsTokens() {
  const src = readFileSync(join(PHONE, "src/theme.generated.ts"), "utf8")
  const block = (name) => {
    const m = new RegExp(`export const ${name} = \\{([\\s\\S]*?)\\n\\}`).exec(src)
    const out = {}
    for (const l of m[1].split("\n")) { const k = /^\s*(\w+): (.+),$/.exec(l); if (k) out[k[1]] = JSON.parse(k[2]) }
    return out
  }
  return { light: block("lightTokens"), dark: block("darkTokens") }
}

function ktPalettes() {
  const src = readFileSync(join(KT_DIR, "AuraTokens.kt"), "utf8")
  const pal = (name) => {
    const m = new RegExp(`val ${name} = Palette\\(([\\s\\S]*?)\\n  \\)`).exec(src)
    const out = {}
    for (const l of m[1].split("\n")) { const k = /^\s*(\w+) = 0xFF([0-9A-F]{6})\.toInt\(\),$/.exec(l); if (k) out[k[1]] = "#" + k[2].toLowerCase() }
    return out
  }
  return { light: pal("Light"), dark: pal("Dark") }
}

function xmlColors(dir) {
  const src = readFileSync(join(MAIN, `res/${dir}/aura_tokens.xml`), "utf8")
  const out = {}
  for (const m of src.matchAll(/<color name="aura_tok_([a-z0-9_]+)">#FF([0-9A-F]{6})<\/color>/g)) out[m[1]] = "#" + m[2].toLowerCase()
  return out
}

const snake = (k) => k.replace(/[A-Z0-9]/g, (c) => "_" + c.toLowerCase()).replace(/_(\d)/g, "$1")

function lum(hex) {
  const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((v) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4))
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
}
export function ratio(a, b) {
  const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p)
  return (x + 0.05) / (y + 0.05)
}

// [premier plan, fond, minimum] : 4,5 pour du texte, 3 pour une bordure de contrôle ou un focus (WCAG 1.4.11)
export const PAIRS = [
  ...["bg", "surface", "surface2"].flatMap((bg) => [
    ["text", bg, 4.5], ["muted", bg, 4.5], ["accent", bg, 4.5], ["ok", bg, 4.5], ["alert", bg, 4.5], ["danger", bg, 4.5],
    ["lineStrong", bg, 3], ["focusRing", bg, 3],
  ]),
  ["text", "me", 4.5], ["muted", "me", 4.5],
  ["accentText", "accent", 4.5], ["alertText", "danger", 4.5], ["alertText", "alert", 4.5], ["alertText", "ok", 4.5],
  // lot 20 : glyphe des raccourcis et de la pastille posé sur le dégradé de l'orbe
  ["onAura", "auraViolet", 4.5], ["onAura", "auraCyan", 4.5],
]

// app.css vit chez le serveur, pas dans ce depot : la comparaison ne tourne que si AURA_PWA_CSS le designe
test("app.css et les trois sorties générées sont à jour (sync-theme --check)", { skip: !process.env.AURA_PWA_CSS }, () => {
  execFileSync(process.execPath, [join(PHONE, "scripts/sync-theme.mjs"), "--check"], { stdio: "pipe" })
})

test("TS, Kotlin et XML portent les mêmes couleurs, dans les deux thèmes", () => {
  const ts = tsTokens(), kt = ktPalettes(), day = xmlColors("values"), night = xmlColors("values-night")
  const keys = Object.keys(kt.dark)
  assert.ok(keys.length >= 20, `trop peu de couleurs natives : ${keys.length}`)
  for (const k of keys) {
    assert.equal(kt.dark[k], String(ts.dark[k]).toLowerCase(), `sombre ${k} : Kotlin ≠ TS`)
    assert.equal(kt.light[k], String(ts.light[k]).toLowerCase(), `clair ${k} : Kotlin ≠ TS`)
    assert.equal(night[snake(k)], kt.dark[k], `sombre ${k} : XML ≠ Kotlin`)
    assert.equal(day[snake(k)], kt.light[k], `clair ${k} : XML ≠ Kotlin`)
    assert.equal(day["night_" + snake(k)], kt.dark[k], `HUD ${k} : sombre fixe ≠ Kotlin`)
  }
})

for (const scheme of ["dark", "light"]) {
  test(`contrastes de la charte, thème ${scheme === "dark" ? "sombre" : "clair"}`, () => {
    const t = tsTokens()[scheme]
    const bad = PAIRS.filter(([fg, bg, min]) => ratio(t[fg], t[bg]) < min)
      .map(([fg, bg, min]) => `${fg} ${t[fg]} sur ${bg} ${t[bg]} : ${ratio(t[fg], t[bg]).toFixed(2)} < ${min}`)
    assert.deepEqual(bad, [])
  })
}

test("décision 2 du 22/10 : gris secondaire sombre éclairci (#b0b6c6, plus le #9ca3b6 de Lc 51)", () => {
  assert.equal(tsTokens().dark.muted, "#b0b6c6")
})

test("l'ancienne charte bleue et la palette violette à part ont disparu du natif", () => {
  // valeurs listées par l'audit du 22/10 (§3.1 « À retirer »)
  const OLD = ["#6b8ff5", "#101418", "#182028", "#26303a", "#e8edf2", "#8a97a6", "#1d2a44", "#6d5ae6", "#a78bfa", "#8f7cf5",
    "#14111f", "#3b2f63", "#221c38", "#2a2150", "#0c0d18", "#8c92ab", "#5be3a1", "#ffc266", "#ff6b7a", "#5cd6f0"]
  const found = []
  for (const f of readdirSync(KT_DIR).filter((n) => n.endsWith(".kt"))) {
    const s = readFileSync(join(KT_DIR, f), "utf8").toLowerCase()
    for (const c of OLD) if (s.includes(`"${c}"`) || s.includes(`0xff${c.slice(1)}`)) found.push(`${f} ${c}`)
  }
  for (const dir of ["values", "values-night"]) {
    for (const f of readdirSync(join(MAIN, "res", dir)).filter((n) => n.endsWith(".xml"))) {
      const s = readFileSync(join(MAIN, "res", dir, f), "utf8").toLowerCase()
      for (const c of OLD) if (s.includes(c.slice(1))) found.push(`${dir}/${f} ${c}`)
    }
  }
  assert.deepEqual(found, [])
})
