import { useFocusEffect } from "@react-navigation/native"
import { useCallback, useEffect, useState } from "react"
import { AppState } from "react-native"
import { AuraDevice, readPermissions, type Permissions } from "./native"

/**
 * Permissions relues quand l'écran reprend le focus et quand l'app revient au premier plan : l'utilisateur active
 * l'accessibilité (ou une autre permission) dans les réglages d'Android, puis revient. Avant, la carte
 * « Contrôle d'apps » lisait l'état une seule fois et affichait « désactivé » jusqu'au prochain démarrage.
 * `declare` : prévient aussi le natif, qui redéclare ses actions au serveur (device_caps) si quelque chose a changé.
 */
export function usePermissions(declare = false): { perms: Permissions, refresh: () => void } {
  const [perms, setPerms] = useState<Permissions>(readPermissions)
  const refresh = useCallback(() => {
    setPerms(readPermissions())
    if (declare) AuraDevice.permissionsChanged()
  }, [declare])
  useFocusEffect(refresh)
  useEffect(() => {
    const sub = AppState.addEventListener("change", (st) => { if (st === "active") refresh() })
    return () => sub.remove()
  }, [refresh])
  return { perms, refresh }
}
