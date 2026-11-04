import { Alert, PermissionsAndroid, Platform, type Permission } from "react-native"
import { AuraDevice, type Permissions } from "./native"

// Ce qu'Aura peut faire sur ce téléphone : une ligne par accès, partagée par la section « Permissions » de l'écran Appareil
// et l'assistant de démarrage (Onboarding). Les clés sont celles de Perms.status() côté natif.

export type Group = "essentiel" | "utile" | "avance"
// restricted : bloquée par les « paramètres restreints » d'Android 13+ tant qu'on ne les a pas autorisés (app hors Play Store)
// also : clé d'une AUTRE ligne sans laquelle celle-ci ne sert à rien (la caméra exige « Afficher par-dessus », Perms.kt) ;
// elle compte dans `keys` et l'écran dit laquelle manque
export type PermRow = {
  key: string, keys?: string[], label: string, hint: string, group: Group, runtime?: string[], special?: string, restricted?: boolean,
  also?: string
}

const P = (name: string) => `android.permission.${name}`
const API = typeof Platform.Version === "number" ? Platform.Version : parseInt(String(Platform.Version), 10)
export const BACKGROUND_LOCATION = P("ACCESS_BACKGROUND_LOCATION")

export const ROWS: PermRow[] = [
  // Essentiel : sans elles, Aura ne joint pas le téléphone ou ne fait pas les actions de tous les jours
  { key: "notifications", group: "essentiel", label: "Notifications", hint: "Confirmations d'actions, connexion en arrière-plan",
    ...(API >= 33 ? { runtime: [P("POST_NOTIFICATIONS")] } : { special: "notifications" }) },
  { key: "microphone", group: "essentiel", label: "Micro", hint: "Messages vocaux dans le Chat", runtime: [P("RECORD_AUDIO")] },
  { key: "battery_unrestricted", group: "essentiel", label: "Batterie sans restriction", hint: "Rester connecté quand l'app est fermée",
    special: "battery_unrestricted" },
  { key: "contacts", group: "essentiel", label: "Contacts", hint: "Comprendre « appelle Paul », « écris à Léa »", runtime: [P("READ_CONTACTS")] },
  // phone_state : sans elle Android ne livre pas la sonnerie, donc pas de fiche d'appel (le journal le dit)
  { key: "phone", keys: ["phone", "phone_state"], group: "essentiel", label: "Téléphone", hint: "Passer un appel, savoir quand ça sonne",
    runtime: [P("CALL_PHONE"), P("READ_PHONE_STATE")] },
  { key: "sms_send", group: "essentiel", label: "Envoyer des SMS", hint: "Aura rédige, tu confirmes", runtime: [P("SEND_SMS")], restricted: true },
  // Utile : élargit ce qu'Aura sait faire
  { key: "notification_listener", group: "utile", label: "Accès aux notifications", hint: "Lire et répondre (WhatsApp…), musique en cours",
    special: "notification_listener", restricted: true },
  { key: "sms_read", group: "utile", label: "Lire les SMS", hint: "Retrouver un code, un message reçu", runtime: [P("READ_SMS")], restricted: true },
  { key: "call_log", group: "utile", label: "Journal d'appels", hint: "Qui a appelé, rappeler un numéro", runtime: [P("READ_CALL_LOG")], restricted: true },
  { key: "calendar", keys: ["calendar_read", "calendar_write"], group: "utile", label: "Agenda", hint: "Lire et ajouter des rendez-vous",
    runtime: [P("READ_CALENDAR"), P("WRITE_CALENDAR")] },
  { key: "location", group: "utile", label: "Localisation", hint: "« Où suis-je ? », itinéraires", runtime: [P("ACCESS_FINE_LOCATION"), P("ACCESS_COARSE_LOCATION")] },
  { key: "dnd_access", group: "utile", label: "Ne pas déranger", hint: "Mode silencieux à la demande", special: "dnd_access" },
  // app_usage (03/10) : accès spécial, accordé dans les réglages système (« Accès aux données d'utilisation » → Aura)
  { key: "usage_access", group: "utile", label: "Accès à l'utilisation", hint: "Temps d'écran par app (24 h, 7 jours) quand tu le demandes à Aura",
    special: "usage_access" },
  // camera_snap n'est déclarée au serveur qu'avec la caméra ET « Afficher par-dessus » (Perms.kt : la prise de vue
  // s'ouvre app fermée) ; avant (revue du 17/10), la ligne disait « Photo à la demande » sans parler du second accès
  { key: "camera", keys: ["camera", "overlay"], also: "overlay", group: "utile", label: "Caméra",
    hint: "Photo à la demande (« montre-moi ») ; exige aussi « Afficher par-dessus les autres applis »", runtime: [P("CAMERA")] },
  { key: "assistant", group: "utile", label: "Assistant par défaut", hint: "Appui long sur le bouton latéral = Aura, avec l'écran en cours",
    special: "assistant" },
  // Avancé
  { key: "location_background", group: "avance", label: "Localisation app fermée", hint: "Après « Localisation » : choisir « Toujours autoriser »",
    runtime: [BACKGROUND_LOCATION] },
  { key: "overlay", group: "avance", label: "Afficher par-dessus les autres applis", hint: "Ouvrir WhatsApp, Maps, le réveil quand l'app est fermée",
    special: "overlay" },
  { key: "promoted_notifications", group: "avance", label: "Suivi en direct", hint: "Puce « Aura travaille » dans la barre d'état",
    special: "promoted_notifications" },
  { key: "accessibility", group: "avance", label: "Lecture et contrôle d'écran", hint: "Aura lit l'écran, envoie les WhatsApp confirmés et pilote les apps de ta liste blanche",
    special: "accessibility" },
]

export const GROUPS: { id: Group, title: string }[] = [
  { id: "essentiel", title: "Essentiel" },
  { id: "utile", title: "Utile" },
  { id: "avance", title: "Avancé" },
]

// Premiers pas : l'ordre compte. Du plus simple (dialogue Android) au plus long (réglages système), et les permissions
// « restreintes » en dernier : Android 13+ n'offre « Autoriser les paramètres restreints » (Infos de l'appli → ⋮) qu'APRÈS
// un premier essai refusé. Cet état ne se lit pas par API : les étapes restreintes portent leur propre bouton de déblocage.
export type Step = { id: string, label: string, hint: string, rows: string[], unlock?: boolean, optional?: boolean }
export const STEPS: Step[] = [
  { id: "notif", label: "Notifications", hint: "Pour voir les confirmations d'actions.", rows: ["notifications"] },
  { id: "mic", label: "Micro", hint: "Pour les messages vocaux.", rows: ["microphone"] },
  { id: "battery", label: "Batterie sans restriction", hint: "Sinon Android coupe la connexion au serveur.", rows: ["battery_unrestricted"] },
  { id: "people", label: "Contacts et téléphone", hint: "Pour « appelle Paul » et pour savoir quand le téléphone sonne (fiche d'appel).", rows: ["contacts", "phone"] },
  { id: "sms", label: "SMS", hint: "Envoyer (avec ta confirmation) et lire les codes reçus. Refusé d'office : « Débloquer ».",
    rows: ["sms_send", "sms_read"] },
  { id: "listener", label: "Accès aux notifications", hint: "Active Aura dans la liste. Interrupteur grisé : « Débloquer », puis réessaie.",
    rows: ["notification_listener"] },
  { id: "calls", label: "Journal d'appels", hint: "Pour reconnaître qui t'appelle (fiche client à la sonnerie) et signaler les appels manqués. Refusé d'office : « Débloquer ».",
    rows: ["call_log"] },
  { id: "a11y", label: "Lecture et contrôle d'écran", hint: "Aura lit l'écran et pilote les apps de ta liste « Contrôle d'apps ». Interrupteur grisé : « Débloquer », puis réessaie.",
    rows: ["accessibility"], unlock: true, optional: true },
  { id: "overlay", label: "Affichage par-dessus", hint: "Pour qu'Aura ouvre WhatsApp, Maps ou le réveil quand l'app est fermée.", rows: ["overlay"], optional: true },
  { id: "usage", label: "Accès à l'utilisation", hint: "Pour « combien de temps sur mon téléphone aujourd'hui ? ». Active Aura dans « Accès aux données d'utilisation ». Grisé : « Débloquer ».",
    rows: ["usage_access"], unlock: true, optional: true },
  { id: "assistant", label: "Assistant par défaut", hint: "Appui long sur le bouton latéral = Aura, avec l'écran en cours. Choisis Aura dans « Assistant numérique ».",
    rows: ["assistant"], optional: true },
]

export const RESTRICTED_HOW = "Infos de l'appli → menu ⋮ en haut à droite → « Autoriser les paramètres restreints »."

/** Pref : l'assistant de démarrage a été terminé ou passé (il ne se rouvre plus seul, seulement depuis Appareil). */
export const ONBOARDING_PREF = "onboarding_done"

export function byKey(key: string): PermRow | undefined {
  return ROWS.find((r) => r.key === key)
}

export function isGranted(perms: Permissions, row: PermRow): boolean {
  return (row.keys ?? [row.key]).every((k) => perms[k])
}

export function stepDone(perms: Permissions, st: Step): boolean {
  return st.rows.every((k) => { const r = byKey(k); return !r || isGranted(perms, r) })
}

export function stepRestricted(st: Step): boolean {
  return !!st.unlock || st.rows.some((k) => byKey(k)?.restricted)
}

/** Dialogues Android ; « ne plus demander » : renvoi vers Infos de l'appli (avec le chemin des paramètres restreints). */
export async function requestRuntime(list: string[]): Promise<void> {
  if (!list.length) return
  const res = await PermissionsAndroid.requestMultiple(list as Permission[])
  if (Object.values(res).some((v) => v === "never_ask_again")) {
    Alert.alert("Permission bloquée", "Android ne redemande plus : active-la dans Infos de l'appli → Autorisations. " +
      "Si elle est grisée, autorise d'abord les paramètres restreints (menu ⋮).",
    [{ text: "Plus tard" }, { text: "Ouvrir", onPress: () => AuraDevice.openSettings("app_info") }])
  }
}

/** Ce qui manque à une ligne à deux accès (« Caméra accordée, manque : Afficher par-dessus… »), null si rien ou une seule clé. */
export function missingAlso(perms: Permissions, row: PermRow): string | null {
  if (!row.also || !perms[row.key] || perms[row.also]) return null
  return byKey(row.also)?.label ?? row.also
}

/** Une ligne : dialogue Android, ou la page des réglages système pour un accès spécial. */
export async function grantRow(perms: Permissions, row: PermRow): Promise<void> {
  // accès principal déjà là, second accès manquant : on ouvre directement le réglage du second (« Afficher par-dessus »)
  const also = row.also ? byKey(row.also) : undefined
  if (also?.special && perms[row.key] && !perms[also.key]) {
    AuraDevice.openSettings(also.special)
    return
  }
  if (row.special) {
    AuraDevice.openSettings(row.special)
    return
  }
  if (row.key === "location_background" && !perms.location) {
    Alert.alert("Localisation d'abord", "Android n'accorde « app fermée » qu'après la localisation normale.")
    return
  }
  await requestRuntime(row.runtime ?? [])
}

/** Tout un groupe en une suite de dialogues (la localisation app fermée reste à part : Android la refuse demandée avec les autres). */
export async function grantGroup(perms: Permissions, group: Group): Promise<void> {
  const missing = ROWS.filter((r) => r.group === group && !isGranted(perms, r) && r.runtime)
  await requestRuntime(missing.flatMap((r) => r.runtime ?? []).filter((p) => p !== BACKGROUND_LOCATION))
}

export async function runStep(perms: Permissions, st: Step): Promise<void> {
  const rows = st.rows.map(byKey).filter((r): r is PermRow => !!r && !isGranted(perms, r))
  const special = rows.find((r) => r.special)
  if (special?.special) {
    AuraDevice.openSettings(special.special)
    return
  }
  await requestRuntime(rows.flatMap((r) => r.runtime ?? []))
}
