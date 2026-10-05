import { useEffect, useState } from "react"
import { Text, View } from "react-native"
import { theme } from "./config"
import { savePause, usePause, type PauseMode } from "./native"
import { Button, Card, Choice, Row, s } from "./ui"

type Duration = { label: string, seconds: number }
const DURATIONS: Duration[] = [
  { label: "15 min", seconds: 15 * 60 },
  { label: "1 h", seconds: 60 * 60 },
  { label: "Jusqu'à la reprise", seconds: 0 },
]
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

// Arret d'urgence. Premier element de Reglages : c'est ce qu'on cherche quand on veut qu'Aura arrete.
export default function PauseCard() {
  const pause = usePause()
  const paused = pause.mode !== "off"
  const [mode, setMode] = useState<Exclude<PauseMode, "off">>("apps")
  const [duration, setDuration] = useState<number>(60 * 60)
  // le choix affiche suit la pause en cours (posee depuis la montre, la notification ou le PC)
  useEffect(() => { if (pause.mode !== "off") setMode(pause.mode) }, [pause.mode])
  const chosen = mode

  const apply = () => {
    savePause(mode, duration > 0 ? Math.floor(Date.now() / 1000) + duration : 0)
  }

  return (
    <View style={{ borderRadius: 14, borderWidth: 2, borderColor: paused ? theme.warn : theme.border }}>
      <Card title="Pause d'Aura">
        <Row>
          <View style={{ width: 14, height: 14, borderRadius: 7, backgroundColor: paused ? theme.warn : theme.ok }} />
          <View style={{ flex: 1 }} accessible accessibilityLabel={pauseText(pause.mode, pause.until)}>
            <Text style={[s.text, { fontWeight: "700", fontSize: 17, color: paused ? theme.warn : theme.text }]}>{pauseText(pause.mode, pause.until)}</Text>
            {paused && pause.by ? <Text style={s.dim}>Demandée par : {pause.by}</Text> : null}
            {paused && pause.pending ? <Text style={s.dim}>Pas encore transmise au serveur : elle s'applique déjà ici.</Text> : null}
          </View>
        </Row>
        <Text style={s.dim}>
          Arrêt d'urgence : fonctionne aussi hors ligne. Les notifications d'Aura et la conversation restent disponibles. Le
          bouton « Écran » de l'assistant, que tu déclenches toi-même, joint toujours le texte de l'écran à ta demande.
        </Text>
        <Text style={s.text}>Quoi suspendre</Text>
        <Row>
          {MODES.map((m) => (
            <Choice key={m.value} label={m.label} selected={chosen === m.value} onPress={() => setMode(m.value)}
              a11y={`Suspendre ${m.label} : ${m.hint}`} />
          ))}
        </Row>
        <Text style={s.dim}>{MODES.find((m) => m.value === chosen)?.hint}</Text>
        <Text style={s.text}>Combien de temps</Text>
        <Row>
          {DURATIONS.map((d) => (
            <Choice key={d.label} label={d.label} selected={duration === d.seconds} onPress={() => setDuration(d.seconds)} a11y={`Durée : ${d.label}`} />
          ))}
        </Row>
        {paused ? (
          <Row>
            <View style={{ flex: 1 }}><Button label="Reprendre Aura" kind="ok" onPress={() => savePause("off", 0)} /></View>
            <View style={{ flex: 1 }}><Button label="Appliquer ce choix" kind="ghost" onPress={apply} /></View>
          </Row>
        ) : (
          <Button label="Mettre Aura en pause" kind="danger" onPress={apply} />
        )}
      </Card>
    </View>
  )
}
