import { theme } from "./config"
import type { AuraState } from "./native"

// etat de connexion : un titre, puis ce que ca veut dire et quoi faire (avant : le mot seul + l'URL brute)
export const BRIDGE_LABELS: Record<AuraState["bridge"], { title: string, help: string, color: string }> = {
  online: { title: "Connecté au serveur", help: "Aura peut agir sur ce téléphone.", color: theme.ok },
  connecting: { title: "Connexion…", help: "Tentative en cours.", color: theme.warn },
  offline: { title: "Hors ligne", help: "Serveur injoignable : vérifie la connexion réseau (VPN éventuel) du téléphone.", color: theme.bad },
  rejected: { title: "Appareil refusé", help: "Jeton révoqué ou inconnu : réinstalle un build avec un nouveau jeton (scripts/build.sh).", color: theme.bad },
  stopped: { title: "Service arrêté", help: "Le serveur ne peut pas joindre le téléphone. Démarre le service.", color: theme.dim },
}
