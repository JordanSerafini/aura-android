import { memo, useEffect, useState } from "react"
import { Text, View } from "react-native"
import { font } from "./config"
import { savePause, type PauseMode, type PauseState } from "./native"
import { useTheme } from "./theme"
import { Button, Choice, useUi } from "./ui"

type Duration = { label: string, seconds: number }
const DURATIONS: Duration[] = [
  { label: "15 min", seconds: 15 * 60 },
  { label: "1 h", seconds: 60 * 60 },
  { label: "Jusqu'à la reprise", seconds: 0 },
]
const KEEP = -1
const MODES: { value: Exclude<PauseMode, "off">, label: string, hint: string }[] = [
  { value: "apps", label: "Apps", hint: "Aura ne lit plus l'écran et ne touche plus aucune app (lecture d'écran et contrôle d'apps refusés, même pour le test de l'app). Le reste marche (appels, SMS, notifications)." },
  { value: "all", label: "Tout", hint: "Aucune action d'Aura sur le téléphone (seul l'état de l'appareil reste lisible) et plus aucun événement envoyé à Aura. Le contexte du téléphone (batterie, réseau, position, prochain rendez-vous) continue d'être transmis au serveur." },
]

export function hhmm(epochS: number): string {
  return new Date(epochS * 1000).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" })
}

/** « En pause (apps) jusqu'à 15:42 » : même phrase que la notification et la montre (PauseLogic.describe). */
export function pauseText(mode: PauseMode, until: number): string {
  if (mode === "off") return "Aura travaille normalement"
  const what = mode === "all" ? "En pause totale" : "En pause (apps)"
  return `${what} ${until > 0 ? `jusqu'à ${hhmm(until)}` : "jusqu'à la reprise"}`
}

// Arrêt d'urgence, appliqué par le natif MÊME HORS LIGNE : c'est ce que la PWA ne sait pas faire, d'où sa place ici.
// La pause en cours (posée depuis la montre, la notification, la PWA ou le PC) s'affiche telle quelle.
export const PausePanel = memo(function PausePanel({ pause }: { pause: PauseState }) {
  const t = useTheme()
  const s = useUi()
  const paused = pause.mode !== "off"
  const [mode, setMode] = useState<Exclude<PauseMode, "off">>(pause.mode === "off" ? "apps" : pause.mode)
  // pause en cours : « Appliquer ce choix » garde son échéance tant qu'on n'a pas choisi une autre durée (revue du 17/10 :
  // changer seulement le mode la reposait pour 1 h). KEEP = l'échéance actuelle, jusqu'à la reprise comprise.
  const [duration, setDuration] = useState<number>(paused ? KEEP : 60 * 60)
  useEffect(() => {
    if (pause.mode !== "off") { setMode(pause.mode); setDuration(KEEP) } else setDuration((d) => (d === KEEP ? 60 * 60 : d))
  }, [pause.mode, pause.until])

  const apply = () => {
    const until = duration === KEEP ? pause.until : duration > 0 ? Math.floor(Date.now() / 1000) + duration : 0
    savePause(mode, until)
  }
  const keepLabel = pause.until > 0 ? `Garder ${hhmm(pause.until)}` : "Garder"
  const keepA11y = `Durée : garder l'échéance actuelle, ${pause.until > 0 ? `jusqu'à ${hhmm(pause.until)}` : "jusqu'à la reprise"}`
  const durations: (Duration & { a11y?: string })[] = paused ? [{ label: keepLabel, seconds: KEEP, a11y: keepA11y }, ...DURATIONS] : DURATIONS

  return (
    <View style={{ gap: 10 }}>
      <View accessible accessibilityLabel={`${pauseText(pause.mode, pause.until)}${paused && pause.by ? `, demandée par ${pause.by}` : ""}`}>
        <Text style={[s.strong, { fontSize: font.lg, color: paused ? t.warn : t.text }]}>{pauseText(pause.mode, pause.until)}</Text>
        {paused && pause.by ? <Text style={s.dim}>Demandée par : {pause.by}</Text> : null}
        {paused && pause.pending ? <Text style={s.dim}>Pas encore transmise au serveur : elle s'applique déjà ici.</Text> : null}
      </View>
      <Text style={s.dim}>
        Fonctionne aussi hors ligne. Les notifications d'Aura et la conversation restent disponibles. Le bouton « Écran » de
        l'assistant, que tu déclenches toi-même, joint toujours le texte de l'écran à ta demande.
      </Text>
      <Text style={s.text} accessibilityRole="header">Quoi suspendre</Text>
      <View style={s.row} accessibilityRole="radiogroup">
        {MODES.map((m) => (
          <Choice key={m.value} label={m.label} selected={mode === m.value} onPress={() => setMode(m.value)} a11y={`Suspendre ${m.label} : ${m.hint}`} />
        ))}
      </View>
      <Text style={s.dim}>{MODES.find((m) => m.value === mode)?.hint}</Text>
      <Text style={s.text} accessibilityRole="header">Combien de temps</Text>
      <View style={s.row} accessibilityRole="radiogroup">
        {durations.map((d) => (
          <Choice key={d.label} label={d.label} selected={duration === d.seconds} onPress={() => setDuration(d.seconds)} a11y={d.a11y ?? `Durée : ${d.label}`} />
        ))}
      </View>
      {paused ? (
        <View style={s.row}>
          <View style={s.grow}><Button label="Reprendre Aura" kind="ok" onPress={() => savePause("off", 0)} /></View>
          <View style={s.grow}><Button label="Appliquer ce choix" kind="ghost" onPress={apply} /></View>
        </View>
      ) : (
        <Button label="Mettre Aura en pause" kind="danger" onPress={apply} hint="Suspend Aura sur ce téléphone, même hors ligne" />
      )}
    </View>
  )
})
