import { OUTGOING } from "./config"
import type { AuraState } from "./native"
import { ROWS } from "./permissions"
import type { Tone } from "./ui"

// etat de connexion : un titre, puis ce que ca veut dire et quoi faire (avant : le mot seul + l'URL brute)
export const BRIDGE_LABELS: Record<AuraState["bridge"], { title: string, help: string, tone: Tone }> = {
  online: { title: "Connecté au serveur", help: "Aura peut agir sur ce téléphone.", tone: "ok" },
  connecting: { title: "Connexion…", help: "Tentative en cours.", tone: "warn" },
  offline: { title: "Hors ligne", help: "Serveur injoignable : vérifie la connexion réseau (VPN éventuel) du téléphone.", tone: "bad" },
  rejected: { title: "Appareil refusé", help: "Jeton révoqué ou inconnu : réinstalle un build avec un nouveau jeton (scripts/build.sh).", tone: "bad" },
  stopped: { title: "Service arrêté", help: "Le serveur ne peut pas joindre le téléphone. Démarre le service.", tone: "dim" },
}

export function bridgeLabel(bridge: string): { title: string, help: string, tone: Tone } {
  return BRIDGE_LABELS[bridge as AuraState["bridge"]] ?? { title: bridge, help: "", tone: "dim" }
}

// Les 39 actions du téléphone (ActionExecutor.run, docs/PROTOCOL.md) en français : carte de confirmation, feuille d'accord,
// Journal. Avant (revue du 17/10) : « sms_send · expire dans 42 s », et le Journal n'en traduisait que 3. Les sortantes
// gardent le libellé d'OUTGOING (réglages « Demander avant… ») ; une action ajoutée côté Kotlin sans libellé ici s'affiche brute.
export const ACTION_LABELS: Record<string, string> = {
  sms_list: "Lire les SMS",
  call_log: "Lire le journal d'appels",
  contacts_search: "Chercher un contact",
  notif_list: "Lire les notifications",
  notif_dismiss: "Fermer une notification",
  notif_open: "Ouvrir une notification",
  calendar_list: "Consulter l'agenda",
  alarm_set: "Régler une alarme",
  timer_set: "Lancer un minuteur",
  location_get: "Localiser le téléphone",
  device_status: "Lire l'état du téléphone",
  app_usage: "Temps d'écran",
  volume_set: "Régler le volume",
  dnd_set: "Régler « Ne pas déranger »",
  ringer_mode: "Changer le mode de sonnerie",
  flashlight: "Allumer ou éteindre la lampe",
  find_phone: "Faire sonner le téléphone",
  media_control: "Piloter la musique",
  media_now: "Voir la musique en cours",
  open_app: "Ouvrir une app",
  open_url: "Ouvrir un lien",
  navigate: "Lancer un itinéraire",
  clipboard_set: "Copier dans le presse-papiers",
  clipboard_get: "Lire le presse-papiers",
  speak: "Parler à voix haute",
  camera_snap: "Prendre une photo",
  screen_read: "Lecture d'écran",
  ui_act: "Contrôle d'app",
  watch_notify: "Notifier sur la montre",
  watch_vibrate: "Faire vibrer la montre",
  watch_heart_rate: "Mesurer le pouls (montre)",
  watch_steps: "Compter les pas (montre)",
  watch_battery: "Batterie de la montre",
  ...Object.fromEntries(OUTGOING.map((o) => [o.action, o.label])),
}

export function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action
}

// Codes d'erreur des actions (ActionError côté Kotlin) ; l'explication complète part au serveur, le Journal n'a que le code
const ERROR_LABELS: Record<string, string> = {
  refused: "refusée", timeout: "sans réponse", paused: "Aura en pause", failed: "échec",
  unsupported: "non prise en charge par ce téléphone", bad_params: "paramètre manquant ou invalide",
  unavailable: "indisponible pour l'instant", not_found: "introuvable", ambiguous: "plusieurs correspondances",
  contact_not_found: "contact introuvable", send_failed: "non envoyé (réseau ?)", tts_failed: "synthèse vocale en échec",
  wrong_app: "l'app visée n'est pas au premier plan", no_focus: "aucun champ de saisie actif", not_clickable: "élément non cliquable",
  refused_field: "champ de mot de passe, jamais rempli", app_blocked: "app refusée d'office", app_not_allowed: "app hors de la liste blanche",
  watch_unreachable: "montre injoignable",
}

/**
 * « permission:sms_send » -> « permission manquante : Envoyer des SMS ». Le nom qui suit `permission:` est une clé de
 * Perms.status() (pas toujours une action : notification_listener, dnd_access…) : libellé de la section Permissions, là où
 * l'utilisateur l'accorde ; à défaut celui de l'action, puis le nom brut. Code inconnu : tel quel.
 */
export function errorLabel(code: string): string {
  const perm = /^permission:(.*)$/.exec(code)
  if (perm) {
    const name = perm[1]
    const row = ROWS.find((r) => r.key === name || r.keys?.includes(name))
    return name ? `permission manquante : ${row?.label ?? actionLabel(name)}` : "permission manquante"
  }
  return ERROR_LABELS[code] ?? code
}
