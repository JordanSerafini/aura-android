import Constants from "expo-constants"
import { darkTokens, lightTokens } from "./theme.generated"

type Extra = { deviceToken?: string, poste?: string, bridgeUrl?: string, pwaUrl?: string, appVersion?: string }

const extra = (Constants.expoConfig?.extra ?? {}) as Extra

export const DEVICE_TOKEN = extra.deviceToken ?? ""
export const POSTE = extra.poste ?? "s22-natif"
export const BRIDGE_URL = extra.bridgeUrl ?? "wss://example.invalid/ws"
export const PWA_URL = extra.pwaUrl ?? "https://example.invalid/app/"
// regex et non `new URL().origin` : non implemente par le polyfill Hermes de certaines versions de React Native
export const PWA_ORIGIN = /^https?:\/\/[^/?#]+/i.exec(PWA_URL)?.[0] ?? ""
export const APP_VERSION = extra.appVersion ?? "1.0.0"

// Actions « sortantes » : confirmation par defaut cote bridge (docs/PROTOCOL.md §1)
export const OUTGOING: { action: string, label: string }[] = [
  { action: "sms_send", label: "Envoyer un SMS" },
  { action: "call", label: "Passer un appel" },
  { action: "notif_reply", label: "Répondre à une notification" },
  { action: "message_send", label: "Message WhatsApp, Telegram…" },
  { action: "calendar_add", label: "Ajouter à l'agenda" },
  { action: "email_compose", label: "Préparer un mail" },
]

// ─── Thème : dérivé des jetons de la PWA (src/theme.generated.ts, scripts/sync-theme.mjs) ───
// Une seule source pour les couleurs : un changement de palette dans pwa/app.css se reporte ici en relançant le script.
// Réglage « auto | light | dark » : celui de la PWA (Réglages → Apparence), relayé par la WebView (theme.tsx).

export type Scheme = "light" | "dark"
export type ThemePref = "auto" | Scheme
type Tokens = Record<string, string | number>

const str = (t: Tokens, key: string, fallback: string): string => (typeof t[key] === "string" ? (t[key] as string) : fallback)
const num = (t: Tokens, keys: string[], fallback: number): number => {
  for (const k of keys) if (typeof t[k] === "number") return t[k] as number
  return fallback
}

/** #rrggbb + opacité -> rgba() (fonds teintés : pastille d'onglet, encadré d'aide). Une autre forme est rendue telle quelle. */
export function alpha(color: string, a: number): string {
  const m = /^#([0-9a-f]{6})$/i.exec(color)
  if (!m) return color
  const n = parseInt(m[1], 16)
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`
}

/** `rgb(0 0 0 / 40%)` (syntaxe CSS 4 de la PWA) -> `rgba(0,0,0,0.4)`, forme sûre pour React Native ; le reste tel quel. */
export function cssColor(color: string): string {
  const m = /^rgba?\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)\s*(?:[/,]\s*([\d.]+)(%)?)?\s*\)$/i.exec(color.trim())
  if (!m) return color
  const a = m[4] === undefined ? 1 : m[5] ? Number(m[4]) / 100 : Number(m[4])
  return `rgba(${m[1]},${m[2]},${m[3]},${a})`
}

function buildTheme(t: Tokens, scheme: Scheme) {
  const accent = str(t, "accent", "#a395ff")
  const warn = str(t, "alert", "#fb923c")
  const text = str(t, "text", "#eceef6")
  // texte posé sur les aplats colorés : --accent-text et --alert-text de la PWA (contraste AA vérifié dans les deux thèmes)
  const onStatus = str(t, "alertText", scheme === "dark" ? "#1c1206" : "#ffffff")
  return {
    scheme,
    bg: str(t, "bg", "#0e1017"),
    card: str(t, "surface", "#161925"),
    // feuilles, menus, dialogues : surface-2 de la charte (22/10), plus claire que la carte en sombre (élévation par la teinte)
    sheet: str(t, "surface2", scheme === "dark" ? "#1c2030" : "#ffffff"),
    border: str(t, "line", "#262b3a"),
    // contour des commandes (boutons secondaires, champs, choix) : --line-strong, 3:1 sur le fond comme dans la PWA
    control: str(t, "lineStrong", scheme === "dark" ? "#6a7188" : "#848a9a"),
    text,
    dim: str(t, "muted", "#b0b6c6"),
    accent,
    ok: str(t, "ok", "#4ade80"),
    warn,
    bad: str(t, "danger", "#f87171"),
    onAccent: str(t, "accentText", "#130d33"),
    onBad: onStatus,
    onOk: onStatus,
    onWarn: onStatus,
    me: str(t, "me", "#262650"),
    violet: str(t, "auraViolet", "#a99aff"),
    cyan: str(t, "auraCyan", "#82f2e8"),
    accentSoft: alpha(accent, 0.18),
    // lisere de l'element actif (pastille de l'onglet), comme --aura-violet a 42 % dans la PWA
    glowLine: alpha(str(t, "auraViolet", "#a99aff"), 0.42),
    warnSoft: alpha(warn, 0.12),
    warnLine: alpha(warn, 0.45),
    ripple: alpha(text, 0.12),
    scrim: cssColor(str(t, "overlay", "rgba(0,0,0,0.45)")),
  }
}

export type Theme = ReturnType<typeof buildTheme>

export const themes: Record<Scheme, Theme> = {
  light: buildTheme(lightTokens, "light"),
  dark: buildTheme(darkTokens, "dark"),
}

// Échelles partagées avec la PWA (--space-*, --radius-*, --text-* de :root, ajoutées le 09/10) ; à défaut, les valeurs
// historiques de l'app. Les tailles de police sont des points de départ : la taille de police du système s'y applique.
const T = darkTokens as Tokens
export const space = {
  xs: num(T, ["space1", "spaceXs"], 4),
  sm: num(T, ["space2", "spaceSm"], 8),
  md: num(T, ["space3", "spaceMd"], 12),
  lg: num(T, ["space4", "spaceLg"], 16),
  xl: num(T, ["space6", "spaceXl"], 24),
  xxl: num(T, ["space8", "spaceXxl"], 40),
}
export const radius = {
  sm: num(T, ["radiusSm"], 8),
  md: num(T, ["radiusMd"], 12),
  lg: num(T, ["radiusLg", "radius"], 16),
  xl: num(T, ["radiusXl"], 24),
  pill: num(T, ["radiusPill"], 999),
}
export const font = {
  xs: num(T, ["textXs", "fsXs"], 12),     // badges, libellés d'onglet
  sm: num(T, ["textSm", "fsSm"], 14),     // mentions, détails techniques (13 avant la charte du 22/10)
  md: num(T, ["textMd", "fsMd"], 14),     // texte secondaire, boutons, choix
  base: num(T, ["textBase", "fsBase"], 16), // corps de texte, titres de carte
  lg: num(T, ["textLg", "fsLg"], 18),     // titre d'écran secondaire, état de la pause
  xl: num(T, ["textXl", "fsXl"], 24),     // titre d'écran, état plein écran
}
/** Police des titres (Space Grotesk, embarquée par le plugin expo-font d'app.config.ts) : la même que la PWA. Sur Android la
 *  graisse est dans le nom de la famille ; ne PAS ajouter de fontWeight (le système épaissirait la police déjà grasse). */
export const display = { medium: "SpaceGrotesk-Medium", bold: "SpaceGrotesk-Bold" }
/** Geist Mono Medium (OFL, embarquée par expo-font ; charte du 22/10, lot 20) : heures, compteurs, durées. Pas de fontWeight. */
export const mono = "GeistMono-Medium"
/** Courbes et durées du mouvement (--ease-spring, --dur-sheet de la PWA) : ressort léger, sans rebond visible. */
export const motion = { spring: { damping: 18, stiffness: 240, mass: 1 }, fast: 120 }
/** Cible tactile minimale (Material : 48 dp ; --tap de la PWA). */
export const TOUCH = num(T, ["tap"], 48)
