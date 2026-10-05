import { useFocusEffect } from "@react-navigation/native"
import { useCallback, useEffect, useState } from "react"
import { AppState } from "react-native"
import { readPermissions, type Permissions } from "./native"

/**
 * Permissions relues quand l'ecran reprend le focus et quand l'app revient au premier plan : l'utilisateur active
 * l'accessibilite (ou une autre permission) dans les reglages d'Android, puis revient. Avant, la carte
 * « Controle d'apps » lisait l'etat une seule fois et affichait « desactive » jusqu'au prochain demarrage.
 */
export function usePermissions(): { perms: Permissions, refresh: () => void } {
  const [perms, setPerms] = useState<Permissions>(readPermissions)
  const refresh = useCallback(() => setPerms(readPermissions()), [])
  useFocusEffect(refresh)
  useEffect(() => {
    const sub = AppState.addEventListener("change", (st) => { if (st === "active") refresh() })
    return () => sub.remove()
  }, [refresh])
  return { perms, refresh }
}
