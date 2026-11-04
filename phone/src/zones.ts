// Zones Maison / Travail (docs/PROTOCOL.md §7.3, `phone_zones`) telles que le bridge les a renvoyées dans son dernier
// `settings` : lues par l'écran Appareil → Réglages du téléphone (revue du 17/10 : on ne les voyait jamais, et un bouton
// écrasait l'autre sans rien dire). Le bridge ne garde que {name, lat, lon, radius_m} : pas de date d'enregistrement.

export type Zone = { name: string, lat: number, lon: number, radius: number }

/** Les deux zones que l'écran propose toujours, même absentes. */
export const MAIN_ZONES = ["Maison", "Travail"] as const

function num(v: unknown): number | null {
  const n = typeof v === "number" ? v : Number.NaN
  return Number.isFinite(n) ? n : null
}

/** JSON de getZones() -> zones lisibles ; une entrée sans nom ou sans coordonnées valides est ignorée. */
export function parseZones(raw: string | null | undefined): Zone[] {
  let arr: unknown
  try { arr = JSON.parse(raw ?? "[]") } catch { return [] }
  if (!Array.isArray(arr)) return []
  const out: Zone[] = []
  for (const it of arr) {
    if (!it || typeof it !== "object") continue
    const o = it as Record<string, unknown>
    const name = typeof o.name === "string" ? o.name.trim() : ""
    const lat = num(o.lat)
    const lon = num(o.lon)
    if (!name || lat === null || lon === null || Math.abs(lat) > 90 || Math.abs(lon) > 180) continue
    out.push({ name, lat, lon, radius: num(o.radius_m) ?? 150 })
  }
  return out
}

const key = (name: string) => name.trim().replace(/\s+/g, " ").toLocaleLowerCase("fr-FR")

/** Même règle que le natif (PhoneContext.mergeZone) : casse et espaces ignorés. */
export function findZone(zones: Zone[], name: string): Zone | undefined {
  return zones.find((z) => key(z.name) === key(name))
}

/** Maison et Travail d'abord (présentes ou non), puis les autres zones connues, dans l'ordre du bridge. */
export function zoneRows(zones: Zone[]): { name: string, zone?: Zone }[] {
  const main = MAIN_ZONES.map((name) => ({ name, zone: findZone(zones, name) }))
  const others = zones.filter((z) => !MAIN_ZONES.some((m) => key(m) === key(z.name))).map((z) => ({ name: z.name, zone: z }))
  return [...main, ...others]
}

/** Coordonnées arrondies au millième (≈ 100 m) : de quoi reconnaître l'endroit, pas une adresse au mètre. */
export function zoneCoords(z: Zone): string {
  const f = (n: number) => n.toLocaleString("fr-FR", { minimumFractionDigits: 3, maximumFractionDigits: 3 })
  return `${f(z.lat)} ; ${f(z.lon)}`
}

export function zoneSummary(z: Zone): string {
  return `rayon ${Math.round(z.radius)} m · ${zoneCoords(z)}`
}
