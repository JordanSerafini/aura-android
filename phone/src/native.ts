import { useEffect, useState } from "react"
import AuraDevice from "../modules/aura-device"

export { AuraDevice }

export type WatchState = { connected: boolean, name: string | null, lastSeen: number, error: string | null }
export type AuraState = {
  service: boolean
  bridge: "stopped" | "connecting" | "online" | "offline" | "rejected"
  bridgeDetail: string
  device: string | null
  settings: Record<string, unknown> | null
  phoneConfirm: Record<string, boolean> | null
  capsSent: boolean
  watch: WatchState
}
// Journal Aura unifié (JournalLogic.kt) : événements du téléphone, actions exécutées et gestes ui_act.
// Les entrées d'action gardent les champs de l'ancien journal (action, source, confirm, ok, error, ms).
export type JournalStatus = "sent" | "queued" | "offline" | "filtered" | "blocked" | "paused" | "ok" | "failed" | "refused" | "timeout"
export type JournalEntry = {
  ts: number, type: "event" | "action", kind: string, summary: string, status: JournalStatus, detail?: string,
  action?: string, source?: string, confirm?: boolean, ok?: boolean, error?: string | null, ms?: number, app?: string, op?: string,
  masked?: boolean
}
export type Confirm = { action_id: string, action: string, summary: string, deadline: number }
export type Permissions = Record<string, boolean>

function parse<T>(raw: string | null | undefined, fallback: T): T {
  if (!raw) return fallback
  try { return JSON.parse(raw) as T } catch { return fallback }
}

export function readState(): AuraState {
  return parse<AuraState>(AuraDevice.getState(), {
    service: false, bridge: "stopped", bridgeDetail: "", device: null, settings: null, phoneConfirm: null, capsSent: false,
    watch: { connected: false, name: null, lastSeen: 0, error: null },
  })
}

export function readPermissions(): Permissions {
  return parse<Permissions>(AuraDevice.getPermissions(), {})
}

export function useAuraState(): AuraState {
  const [state, setState] = useState<AuraState>(readState)
  useEffect(() => {
    const sub = AuraDevice.addListener("onState", (e) => setState(parse(e.json, readState())))
    setState(readState())
    return () => sub.remove()
  }, [])
  return state
}

export function useConfirms(): Confirm[] {
  const [items, setItems] = useState<Confirm[]>(() => parse(AuraDevice.getConfirms(), []))
  useEffect(() => {
    const sub = AuraDevice.addListener("onConfirms", (e) => setItems(parse(e.json, [])))
    return () => sub.remove()
  }, [])
  return items
}

/** Compteur d'usage (nombres seulement, jamais un contenu) : le natif les envoie au bridge au plus une fois par jour. */
export function countUsage(name: string): void {
  try { AuraDevice.countUsage(name) } catch { /* compter ne casse jamais l'écran */ }
}

export const JOURNAL_MAX = 200

export function useJournal(): JournalEntry[] {
  const [items, setItems] = useState<JournalEntry[]>(() => parse(AuraDevice.getLog(), []))
  useEffect(() => {
    const sub = AuraDevice.addListener("onLog", (e) => {
      const entry = parse<JournalEntry | null>(e.json, null)
      if (entry) setItems((prev) => [entry, ...prev].slice(0, JOURNAL_MAX))
    })
    const cleared = AuraDevice.addListener("onLogClear", () => setItems([]))
    setItems(parse(AuraDevice.getLog(), []))
    return () => { sub.remove(); cleared.remove() }
  }, [])
  return items
}

/** « Effacer le journal » : vide la liste côté natif (les écrans la suivent par l'événement onLogClear). */
export function clearJournal(): void {
  AuraDevice.clearLog()
}

export async function runLocal(action: string, params: Record<string, unknown> = {}): Promise<{ ok: boolean, result?: unknown, error?: string, message?: string }> {
  return parse(await AuraDevice.runLocal(action, JSON.stringify(params)), { ok: false, error: "réponse illisible" })
}

export async function pingWatch(): Promise<{ ok: boolean, ms?: number, error?: string }> {
  return parse(await AuraDevice.pingWatch(), { ok: false, error: "réponse illisible" })
}

// Declencheurs (docs/PROTOCOL.md §9.1) et controle d'apps (§9.2) : reglages stockes cote natif
export type EventSettings = {
  enabled: boolean, missed_call: boolean, battery_low: boolean, charger: boolean, zone: boolean, notification: boolean,
  call_ringing: boolean, notif_removed: boolean, notif_apps: string[]
}
export type AppEntry = { package: string, label: string, blocked: boolean }

export const DEFAULT_EVENT_SETTINGS: EventSettings = {
  enabled: true, missed_call: true, battery_low: true, charger: true, zone: true, notification: true, call_ringing: true,
  notif_removed: true, notif_apps: [],
}

export function readEventSettings(): EventSettings {
  return { ...DEFAULT_EVENT_SETTINGS, ...parse<Partial<EventSettings>>(AuraDevice.getEventSettings(), {}) }
}

/** Enregistre et rend ce que le natif a reellement retenu (paquets sensibles retires). */
export function saveEventSettings(next: EventSettings): EventSettings {
  return { ...DEFAULT_EVENT_SETTINGS, ...parse<Partial<EventSettings>>(AuraDevice.setEventSettings(JSON.stringify(next)), {}) }
}

export function readUiWhitelist(): string[] {
  return parse<string[]>(AuraDevice.getUiWhitelist(), [])
}

export function saveUiWhitelist(list: string[]): string[] {
  return parse<string[]>(AuraDevice.setUiWhitelist(JSON.stringify(list)), [])
}

export async function listApps(): Promise<AppEntry[]> {
  return parse<AppEntry[]>(await AuraDevice.listApps(), [])
}

// Pause d'Aura (docs/PROTOCOL.md §10) : arret d'urgence applique localement par le natif, meme hors ligne.
// off = normal | apps = plus de lecture d'ecran ni de controle d'apps | all = aucune action sur le telephone, aucun evenement
export type PauseMode = "off" | "apps" | "all"
export type PauseState = { mode: PauseMode, until: number, by: string, pending?: boolean }
const NO_PAUSE: PauseState = { mode: "off", until: 0, by: "" }

export function readPause(): PauseState {
  return parse<PauseState>(AuraDevice.getPauseState(), NO_PAUSE)
}

/** until : epoch secondes, 0 = jusqu'a la reprise. Rend l'etat retenu. */
export function savePause(mode: PauseMode, until: number): PauseState {
  return parse<PauseState>(AuraDevice.setPause(mode, until), NO_PAUSE)
}

/** Etat de pause vivant (evenement natif) ; l'echeance est rejouee ici : une pause echue s'affiche « off » sans attendre le natif. */
export function usePause(): PauseState {
  const [state, setState] = useState<PauseState>(readPause)
  useEffect(() => {
    const sub = AuraDevice.addListener("onPause", (e) => setState(parse(e.json, NO_PAUSE)))
    setState(readPause())
    return () => sub.remove()
  }, [])
  useEffect(() => {
    if (state.mode === "off" || state.until <= 0) return
    const ms = state.until * 1000 - Date.now()
    const t = setTimeout(() => setState(readPause()), Math.max(0, ms) + 300)
    return () => clearTimeout(t)
  }, [state])
  return state.mode !== "off" && state.until > 0 && Date.now() >= state.until * 1000 ? NO_PAUSE : state
}

export function isPaused(p: PauseState): boolean {
  return p.mode !== "off"
}

export function relTime(ts: number): string {
  if (!ts) return "jamais"
  const s = Math.round((Date.now() - ts) / 1000)
  if (s < 60) return "à l'instant"
  if (s < 3600) return `il y a ${Math.round(s / 60)} min`
  if (s < 86400) return `il y a ${Math.round(s / 3600)} h`
  return new Date(ts).toLocaleString("fr-FR")
}
