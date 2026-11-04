import { memo, useCallback, useEffect, useState } from "react"
import { AppState, Text, View } from "react-native"
import { AuraDevice } from "./native"
import { Button, Dot, ToggleRow, useUi } from "./ui"

// HUD hors de l'écran d'accueil (1.9.0, 22/10, PROTOCOL.md §35) : le flux du widget « Aura HUD » alimente aussi la
// notification d'Aura, une Live Update (Now Bar), la tuile des Réglages rapides et la montre (HudSurfaces.kt)
export type HudState = {
  notif: boolean, live_grave: boolean, live_rdv: boolean, live_missions: boolean, watch: boolean,
  tile_added: boolean, widgets: number, can_promote: boolean, android: number, watch_connected: boolean, feed_at: number,
  summary: string,
}

function readHud(): HudState | null {
  if (!AuraDevice.getHud) return null
  try { return JSON.parse(AuraDevice.getHud()) as HudState } catch { return null }
}

export function hudSummary(h: HudState | null): string {
  if (!h) return "Indisponible"
  const n = [h.widgets > 0, h.notif, h.live_grave || h.live_rdv || h.live_missions, h.tile_added, h.watch].filter(Boolean).length
  return `${h.summary} · ${n} endroit${n > 1 ? "s" : ""}`
}

export function useHud(): [HudState | null, (patch: Partial<HudState>) => void] {
  const [h, setH] = useState<HudState | null>(readHud)
  useEffect(() => {
    // retour des réglages système (Live Updates, tuile ajoutée) : relire
    const sub = AppState.addEventListener("change", (st) => { if (st === "active") setH(readHud()) })
    return () => sub.remove()
  }, [])
  const set = useCallback((patch: Partial<HudState>) => {
    if (!AuraDevice.setHud) return
    try { setH(JSON.parse(AuraDevice.setHud(JSON.stringify(patch))) as HudState) } catch { setH(readHud()) }
  }, [])
  return [h, set]
}

/** Où le HUD apparaît, en dehors du widget : un interrupteur par endroit, et l'ajout en un geste quand Android le permet. */
export const HudSettingsPanel = memo(function HudSettingsPanel({ hud, set }: { hud: HudState | null, set: (p: Partial<HudState>) => void }) {
  const s = useUi()
  const [msg, setMsg] = useState("")
  if (!hud) return <Text style={s.dim}>Le module natif de cette version ne connaît pas ces réglages.</Text>
  const h = hud
  const live = h.live_grave || h.live_rdv || h.live_missions
  return (
    <View style={{ gap: 10 }}>
      <Text style={s.dim}>
        Le flux « Aura HUD » du serveur, ailleurs que sur l'écran d'accueil. Rien ne sonne ni ne vibre pour un simple
        rafraîchissement, et la nuit (23 h-7 h) aucune Live Update ne s'affiche.
      </Text>

      <View style={s.row}>
        <Dot ok={h.widgets > 0} tone={h.widgets > 0 ? "ok" : "dim"} />
        <Text style={[s.text, s.grow]}>{h.widgets > 0 ? `Widget posé (${h.widgets})` : "Widget « Aura HUD » pas encore posé"}</Text>
        <Button small kind="ghost" label="Poser" a11y="Poser le widget Aura HUD sur l'écran d'accueil"
          onPress={() => setMsg(`Widget : ${AuraDevice.hudPinWidget?.() ?? "indisponible"}`)} />
      </View>

      <View style={s.row}>
        <Dot ok={h.tile_added} tone={h.tile_added ? "ok" : "dim"} />
        <View style={s.grow}>
          <Text style={s.text}>Tuile des Réglages rapides</Text>
          <Text style={s.dim}>Glisse deux fois vers le bas : « Aura HUD » et le nombre d'alertes. Toucher = ouvrir Aura.</Text>
        </View>
        {!h.tile_added ? <Button small kind="ghost" label="Ajouter" a11y="Ajouter la tuile Aura HUD"
          onPress={() => setMsg(`Tuile : ${AuraDevice.hudAddTile?.() ?? "indisponible"}`)} /> : null}
      </View>

      <ToggleRow label="Dans la notification d'Aura" hint="Volet de notifications : alertes en titre, prochain rendez-vous, à traiter (déplier pour le détail)"
        value={h.notif} onChange={(v) => set({ notif: v })} />

      <Text style={s.strong} accessibilityRole="header">Live Update (barre d'état, écran verrouillé, Now Bar)</Text>
      <ToggleRow label="Alerte grave" hint="Tant qu'elle dure ; « Vu » l'écarte jusqu'à la suivante" value={h.live_grave}
        onChange={(v) => set({ live_grave: v })} />
      <ToggleRow label="Rendez-vous dans moins de 30 min" hint="Compte à rebours jusqu'au début" value={h.live_rdv}
        onChange={(v) => set({ live_rdv: v })} />
      <ToggleRow label="Mission d'Aura en cours" hint="Coupé par défaut : il y en a souvent" value={h.live_missions}
        onChange={(v) => set({ live_missions: v })} />
      {live && h.android >= 36 && !h.can_promote ? (
        <View style={{ gap: 6 }}>
          <Text style={s.dim}>Les Live Updates d'Aura sont coupées dans Android : sans elles, pas de puce ni de Now Bar.</Text>
          <Button small kind="ghost" label="Autoriser les Live Updates" onPress={() => AuraDevice.openSettings("promoted_notifications")} />
        </View>
      ) : null}

      <ToggleRow label="Sur la montre" hint={h.watch_connected ? "Tuile et complication « Aura HUD » de la montre" : "Montre non reliée en ce moment"}
        value={h.watch} onChange={(v) => set({ watch: v })} />

      <Text style={s.dim}>
        Impossible pour une app hors Samsung : les widgets de l'écran verrouillé et de l'Always On Display (réservés aux apps
        Samsung), et le panneau Edge (fermé aux apps tierces depuis fin 2023).
      </Text>
      {msg ? <Text style={s.small}>{msg}</Text> : null}
    </View>
  )
})
