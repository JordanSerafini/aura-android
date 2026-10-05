import Constants from "expo-constants"

type Extra = { deviceToken?: string, poste?: string, bridgeUrl?: string, pwaUrl?: string, appVersion?: string }

const extra = (Constants.expoConfig?.extra ?? {}) as Extra

export const DEVICE_TOKEN = extra.deviceToken ?? ""
export const POSTE = extra.poste ?? "s22-natif"
export const BRIDGE_URL = extra.bridgeUrl ?? "wss://example.invalid/ws"
export const PWA_URL = extra.pwaUrl ?? "https://example.invalid/app/"
export const PWA_ORIGIN = new URL(PWA_URL).origin
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

export const theme = {
  bg: "#101418",
  card: "#182028",
  border: "#26303a",
  text: "#e8edf2",
  dim: "#8a97a6",
  accent: "#6b8ff5",
  ok: "#4ade80",
  warn: "#fb923c",
  bad: "#f87171",
  // texte sur les aplats clairs du theme sombre (comme la PWA, --accent-text) : le blanc faisait 3,1:1 sur
  // accent, 2,8:1 sur bad et 2,3:1 sur le vert des boutons « Accepter » (AA demande 4,5:1)
  onAccent: "#0b1530",
  onBad: "#1f0a0a",
  onOk: "#052e14",
}
