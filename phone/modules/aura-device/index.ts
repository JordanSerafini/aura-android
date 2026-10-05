import { requireNativeModule } from "expo"

type Sub = { remove: () => void }

export interface AuraDeviceNative {
  configure(url: string, token: string, poste: string, version: string): void
  startService(): void
  stopService(): void
  getState(): string
  setScreen(name: string): void
  trackRequest(id: string): void
  canPostPromoted(): boolean
  countUsage(name: string): void
  isDefaultAssistant(): boolean
  getPhoneContext(): string
  getZones(): string
  setZoneHere(name: string): Promise<string>
  openAssistantSettings(): boolean
  talkStart(): string
  talkTap(): void
  talkStop(): void
  talkState(): string
  getEventSettings(): string
  setEventSettings(json: string): string
  getUiWhitelist(): string
  setUiWhitelist(json: string): string
  listApps(): Promise<string>
  getLog(): string
  clearLog(): void
  getPauseState(): string
  setPause(mode: string, until: number): string
  getConfirms(): string
  getPermissions(): string
  permissionsChanged(): void
  resolveConfirm(id: string, ok: boolean): boolean
  pingWatch(): Promise<string>
  refreshWatch(): Promise<string>
  sendBridgeSettings(json: string): boolean
  setWatchSettings(json: string): void
  getWatchSettings(): string
  openSettings(kind: string): boolean
  getPref(key: string): string | null
  setPref(key: string, value: string): void
  getInfo(): string
  runLocal(action: string, params: string): Promise<string>
  addListener(event: "onState" | "onLog" | "onLogClear" | "onConfirms" | "onTalk" | "onPause", fn: (e: { json: string }) => void): Sub
}

export default requireNativeModule<AuraDeviceNative>("AuraDevice")
