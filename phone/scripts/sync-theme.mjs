#!/usr/bin/env node
// Jetons de thème de la PWA -> les TROIS couches de l'app (charte UI du 22/10, lot 19). Node seul, aucune dépendance.
//   src/theme.generated.ts                     (React Native)
//   modules/aura-device/.../AuraTokens.kt       (natif Kotlin : bulle, partage, assistant, activités)
//   modules/aura-device/.../res/values{,-night}/aura_tokens.xml (widgets HUD et Aura)
// app.css reste la seule source : plus aucune couleur recopiée à la main (test : scripts/sync-theme.test.mjs).
//
// Source : les variables CSS de `:root` (thème clair) et du bloc sombre (`:root[data-theme="dark"]`, sinon le bloc
// `:root:not([data-theme="light"])` de la media query) de pwa/app.css. Le sombre hérite du clair puis le surcharge,
// comme dans le navigateur. `var(--x)` est résolu, `14px` devient 14, `1.5rem` devient 24 ; le reste reste du texte.
//
// Usage : node scripts/sync-theme.mjs [--css chemin/app.css] [--out src/theme.generated.ts] [--check]
//   (--out ne déplace que la sortie TS ; les sorties Kotlin et XML ont un chemin fixe dans le module aura-device)
//   AURA_PWA_CSS donne le chemin de app.css (feuille de style de la PWA servie par le bridge) si --css est absent.
//   --check : n'écrit rien, sort en 1 si le fichier généré n'est plus à jour (pour un build ou une revue).
import { readFileSync, writeFileSync, existsSync } from "node:fs"
import { dirname, resolve } from "node:path"
import { fileURLToPath } from "node:url"

const PHONE_DIR = resolve(dirname(fileURLToPath(import.meta.url)), "..")

function arg(name) {
  const i = process.argv.indexOf(name)
  return i > 0 ? process.argv[i + 1] : undefined
}

const cssArg = arg("--css") ?? process.env.AURA_PWA_CSS
if (!cssArg) {
  console.error("chemin de app.css absent (option --css ou variable AURA_PWA_CSS)")
  process.exit(2)
}
const cssPath = resolve(cssArg)
const outPath = resolve(PHONE_DIR, arg("--out") ?? "src/theme.generated.ts")
const checkOnly = process.argv.includes("--check")

if (!existsSync(cssPath)) {
  console.error(`app.css introuvable : ${cssPath} (option --css ou variable AURA_PWA_CSS)`)
  process.exit(2)
}

const css = readFileSync(cssPath, "utf8").replace(/\/\*[\s\S]*?\*\//g, "")

// blocs les plus internes `sélecteur { déclarations }` : le sélecteur d'un bloc dans une media query est bien le sien
function blocks(src) {
  const out = []
  const re = /([^{}]+)\{([^{}]*)\}/g
  let m
  while ((m = re.exec(src))) out.push({ selector: m[1].trim().replace(/\s+/g, " "), body: m[2] })
  return out
}

function vars(body) {
  const out = {}
  for (const decl of body.split(";")) {
    const m = /^\s*--([a-z0-9-]+)\s*:\s*([\s\S]+?)\s*$/i.exec(decl)
    if (m) out[m[1]] = m[2].replace(/\s+/g, " ")
  }
  return out
}

const all = blocks(css)
const pick = (selectors) => {
  const found = all.filter((b) => selectors.includes(b.selector))
  return found.length ? Object.assign({}, ...found.map((b) => vars(b.body))) : null
}

const light = pick([":root"])
const dark = pick([':root[data-theme="dark"]', ":root[data-theme=dark]"]) ?? pick([':root:not([data-theme="light"])'])
if (!light || !Object.keys(light).length) {
  console.error(`aucune variable dans :root de ${cssPath}`)
  process.exit(3)
}
if (!dark) console.warn("bloc sombre introuvable : le thème sombre reprend le clair")

const camel = (name) => name.replace(/-([a-z0-9])/g, (_, c) => c.toUpperCase())

function resolveAll(map) {
  const done = {}
  const get = (name, depth = 0) => {
    const raw = map[name]
    if (raw === undefined || depth > 10) return undefined
    return raw.replace(/var\(\s*--([a-z0-9-]+)\s*(?:,\s*([^)]+))?\)/gi, (_, ref, fallback) => get(ref, depth + 1) ?? (fallback ?? "").trim())
  }
  for (const name of Object.keys(map)) done[name] = get(name)
  return done
}

// 14px -> 14, 1.5rem -> 24, 1.45 -> 1.45 ; une couleur ou une liste de polices reste du texte
function value(v) {
  const px = /^(-?\d*\.?\d+)px$/.exec(v)
  if (px) return Number(px[1])
  const rem = /^(-?\d*\.?\d+)r?em$/.exec(v)
  if (rem) return Math.round(Number(rem[1]) * 16 * 100) / 100
  if (/^-?\d*\.?\d+$/.test(v)) return Number(v)
  return v
}

function tokens(map) {
  const out = {}
  for (const [name, v] of Object.entries(resolveAll(map))) {
    if (v === undefined || v === "") continue
    out[camel(name)] = value(v)
  }
  return out
}

const lightTokens = tokens(light)
const darkTokens = tokens({ ...light, ...(dark ?? {}) })

const fmt = (o) => "{\n" + Object.entries(o).map(([k, v]) => `  ${k}: ${JSON.stringify(v)},`).join("\n") + "\n}"
const shown = "pwa/app.css"  // jamais le chemin local : il finirait dans les fichiers generes, suivis par git

const text = `// Généré par scripts/sync-theme.mjs depuis ${shown} : ne pas éditer à la main.
// Relancer après un changement des jetons de la PWA : node scripts/sync-theme.mjs
/* eslint-disable */

export const lightTokens = ${fmt(lightTokens)} as const

export const darkTokens = ${fmt(darkTokens)} as const

export type PwaTokens = { [K in keyof typeof lightTokens]: string | number }
`

// ─── sorties natives : seules les couleurs #rrggbb et les échelles numériques passent ───
const HEX = /^#([0-9a-f]{6})$/i
const MOD = resolve(PHONE_DIR, "modules/aura-device/android/src/main")
const ktPath = resolve(MOD, "java/dev/aura/mobile/device/AuraTokens.kt")
const xmlDay = resolve(MOD, "res/values/aura_tokens.xml")
const xmlNight = resolve(MOD, "res/values-night/aura_tokens.xml")

const colorKeys = Object.keys(lightTokens).filter((k) => HEX.test(String(lightTokens[k])) && HEX.test(String(darkTokens[k])))
const argb = (hex) => `0xFF${hex.slice(1).toUpperCase()}.toInt()`
const snake = (k) => k.replace(/[A-Z0-9]/g, (c) => "_" + c.toLowerCase()).replace(/_(\d)/g, "$1")
const numFor = (prefix) => Object.keys(darkTokens).filter((k) => k.startsWith(prefix) && typeof darkTokens[k] === "number")
const kname = (k, prefix) => k.slice(prefix.length).replace(/^[A-Z]/, (c) => c.toUpperCase()).replace(/([a-z])([A-Z])/g, "$1_$2").toUpperCase().replace(/^(\d)/, "S$1") || "BASE"
const ms = (v) => { const m = /^(\d+)ms$/.exec(String(v)); return m ? Number(m[1]) : null }
const durKeys = Object.keys(darkTokens).filter((k) => k.startsWith("dur") && ms(darkTokens[k]) !== null)

const palette = (o) => colorKeys.map((k) => `    ${k} = ${argb(String(o[k]))},`).join("\n")
const kt = `// Généré par scripts/sync-theme.mjs depuis ${shown} : ne pas éditer à la main.
// Relancer après un changement des jetons de la PWA : node scripts/sync-theme.mjs
package dev.aura.mobile.device

/** Jetons de la charte (22/10) : les mêmes que la PWA et React Native. Couleurs en ARGB, tailles en dp/sp, durées en ms. */
object AuraTokens {
  class Palette(
${colorKeys.map((k) => `    val ${k}: Int,`).join("\n")}
  )

  val Light = Palette(
${palette(lightTokens)}
  )

  val Dark = Palette(
${palette(darkTokens)}
  )

  /** Espacements (dp), grille de 4. */
  object Space {
${numFor("space").map((k) => `    const val ${kname(k, "space")} = ${darkTokens[k]}f`).join("\n")}
  }

  /** Rayons (dp). */
  object Radius {
${numFor("radius").map((k) => `    const val ${k === "radius" ? "CARD" : kname(k, "radius")} = ${darkTokens[k]}f`).join("\n")}
  }

  /** Tailles de texte (sp) : 12 méta, 14 secondaire, 16 corps, 18 titre de carte, 24 titre d'écran. */
  object Type {
${numFor("text").map((k) => `    const val ${kname(k, "text")} = ${darkTokens[k]}f`).join("\n")}
  }

  /** Durées du mouvement (ms). */
  object Motion {
${durKeys.map((k) => `    const val ${kname(k, "dur")} = ${ms(darkTokens[k])}L`).join("\n")}
  }

  /** Cible tactile minimale (dp). */
  const val TAP = ${Number(darkTokens.tap) || 48}f
}
`
// aura_tok_* suit le thème (values / values-night) ; aura_tok_night_* est le sombre fixe, pour le widget HUD qui reste
// « instrument de nuit » en thème clair ; *_glass : même teinte à 94 % d'opacité (fond du widget sur le fond d'écran)
const night = (night) => night ? "" : `
${colorKeys.map((k) => `  <color name="aura_tok_night_${snake(k)}">#FF${String(darkTokens[k]).slice(1).toUpperCase()}</color>`).join("\n")}
  <color name="aura_tok_night_glass_bg">#F0${String(darkTokens.bg).slice(1).toUpperCase()}</color>
  <color name="aura_tok_night_glass_surface2">#F0${String(darkTokens.surface2).slice(1).toUpperCase()}</color>`
const xml = (o, isNight) => `<?xml version="1.0" encoding="utf-8"?>
<!-- Généré par scripts/sync-theme.mjs depuis ${shown} (thème ${isNight ? "sombre" : "clair"}) : ne pas éditer à la main. -->
<resources>
${colorKeys.map((k) => `  <color name="aura_tok_${snake(k)}">#FF${String(o[k]).slice(1).toUpperCase()}</color>`).join("\n")}${night(isNight)}
</resources>
`

const outputs = [[outPath, text], [ktPath, kt], [xmlDay, xml(lightTokens, false)], [xmlNight, xml(darkTokens, true)]]
let stale = 0
for (const [path, body] of outputs) {
  const current = existsSync(path) ? readFileSync(path, "utf8") : ""
  if (current === body) continue
  if (checkOnly) { console.error(`${path} n'est plus à jour : node scripts/sync-theme.mjs`); stale++; continue }
  writeFileSync(path, body)
  console.log(`écrit ${path}`)
}
if (checkOnly) {
  if (stale) process.exit(1)
  console.log(`thème à jour (${outputs.length} sorties, ${colorKeys.length} couleurs)`)
  process.exit(0)
}
console.log(`${Object.keys(lightTokens).length} jetons, ${colorKeys.length} couleurs natives`)
