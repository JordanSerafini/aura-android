import { requireNativeModule } from "expo"

type Sub = { remove: () => void }

export interface AuraDeviceNative {
  /** "" si pris en compte, sinon le motif du refus (URL du bridge qui n'est pas wss:// dans un APK de release) */
  configure(url: string, token: string, poste: string, version: string): string
  startService(): void
  stopService(): void
  getState(): string
  setScreen(name: string): void
  trackRequest(id: string): void
  /** Haptique semantique de la PWA (1.4.9) : tick | confirm | reject | heavy ; faux = intention inconnue ou pas d'activite. */
  haptic?(kind: string): boolean
  /** Widget d'écran d'accueil (1.4.9) : « À traiter » (groupe Maintenant) et notifications non lues, vus par la PWA. */
  setWidgetCounts?(actions: number, notifs: number): void
  /** Barres système assorties au thème (1.4.0) : icônes sombres si `light`, fond de fenêtre `background` (#rrggbb). */
  setSystemBars?(light: boolean, background: string): void
  /** Gestes du PC portés au S22 (1.6.0, __auraNativeV 5) : copie libellée « Aura », feuille de partage Android. */
  copyText?(text: string): boolean
  shareText?(text: string, subject: string): boolean
  /** « Ce que j'ai copié » : JSON {age_s} d'un texte copié ailleurs depuis < 15 min, jamais proposé ; "" sinon. */
  clipHint?(): string
  /** Le texte du presse-papiers (15 000 car. au plus), app au premier plan, sur l'appui de l'utilisateur seulement. */
  clipText?(): string
  /** HUD hors de l'écran d'accueil (1.9.0, PROTOCOL.md §35) : réglages en JSON (HudSettingsPanel). */
  getHud?(): string
  /** JSON partiel {notif, live_grave, live_rdv, live_missions, watch} ; rend l'état complet. */
  setHud?(json: string): string
  /** Propose de poser le widget « Aura HUD » sur l'écran d'accueil (lanceur). */
  hudPinWidget?(): string
  /** Propose d'ajouter la tuile « Aura HUD » aux Réglages rapides (Android 13+). */
  hudAddTile?(): string
  countUsage(name: string): void
  /** Zones du dernier `settings` du bridge (JSON [{name, lat, lon, radius_m}]), [] tant qu'il n'a rien envoyé. */
  getZones(): string
  setZoneHere(name: string): Promise<string>
  /** Efface une zone (liste complète renvoyée au bridge sans elle) : JSON {ok, result|error, message}. */
  removeZone(name: string): Promise<string>
  talkStart(): string
  talkTap(): void
  /** « Micro coupé pendant qu'Aura parle » (1.5.0) : pref `talk_mute_speaking` (« 1 »), appliquée à la session en cours. */
  talkSetMute?(on: boolean): void
  talkStop(): void
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
  /** true = réglages enregistrés par Play Services (la montre les reçoit à sa prochaine connexion si elle est absente). */
  setWatchSettings(json: string): Promise<boolean>
  getWatchSettings(): string
  openSettings(kind: string): boolean
  consumeTalkNonce(nonce: string): boolean
  /** Revue sécurité du 27/10 (F1) : ce lien aura://notif/<id>/act|ask?k=… a-t-il été scellé par CE téléphone (NotifLink) ? */
  notifLinkOk?(url: string): boolean
  getPref(key: string): string | null
  setPref(key: string, value: string): void
  getInfo(): string
  runLocal(action: string, params: string): Promise<string>
  addListener(event: "onState" | "onLog" | "onLogClear" | "onConfirms" | "onTalk" | "onPause", fn: (e: { json: string }) => void): Sub
}

export default requireNativeModule<AuraDeviceNative>("AuraDevice")
