import { useState } from "react"
import { Alert, Pressable, ScrollView, Text, TextInput, View } from "react-native"
import { SafeAreaView } from "react-native-safe-area-context"
import { theme } from "../config"
import { AuraDevice, pingWatch, relTime, useAuraState } from "../native"
import { Button, Card, Dot, Row, s } from "../ui"

type VoiceMode = "auto" | "voice" | "text"
type WatchSettings = { voice_mode: VoiceMode, work_hours: { days: number[], start: string, end: string } }

const DAYS = ["L", "M", "M", "J", "V", "S", "D"]
const DAY_NAMES = ["lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"]
const MODES: { value: VoiceMode, label: string, hint: string }[] = [
  { value: "auto", label: "Auto", hint: "Lu à voix haute, sauf aux heures de travail (texte + vibration)" },
  { value: "voice", label: "Voix", hint: "Toujours lu à voix haute" },
  { value: "text", label: "Texte", hint: "Jamais lu : texte sur la montre" },
]
const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/

function load(): WatchSettings {
  try { return JSON.parse(AuraDevice.getWatchSettings()) as WatchSettings } catch {
    return { voice_mode: "auto", work_hours: { days: [1, 2, 3, 4, 5], start: "08:30", end: "17:30" } }
  }
}

export default function WatchScreen() {
  const state = useAuraState()
  const [settings, setSettings] = useState<WatchSettings>(load)
  const [start, setStart] = useState(settings.work_hours.start)
  const [end, setEnd] = useState(settings.work_hours.end)
  const [pinging, setPinging] = useState(false)
  const [dirty, setDirty] = useState(false)
  const w = state.watch

  const update = (next: WatchSettings) => { setSettings(next); setDirty(true) }
  const toggleDay = (d: number) => {
    const days = settings.work_hours.days.includes(d) ? settings.work_hours.days.filter((x) => x !== d) : [...settings.work_hours.days, d].sort()
    update({ ...settings, work_hours: { ...settings.work_hours, days } })
  }
  const save = () => {
    if (!HHMM.test(start) || !HHMM.test(end)) {
      Alert.alert("Horaires", "Format HH:MM attendu, par exemple 08:30.")
      return
    }
    const next = { ...settings, work_hours: { ...settings.work_hours, start, end } }
    AuraDevice.setWatchSettings(JSON.stringify(next))
    setSettings(next)
    setDirty(false)
  }
  const ping = async () => {
    setPinging(true)
    const r = await pingWatch()
    setPinging(false)
    Alert.alert(r.ok ? "Montre joignable" : "Pas de réponse", r.ok ? `Aller-retour : ${r.ms} ms` : r.error ?? "")
  }

  return (
    <SafeAreaView style={s.screen} edges={["top"]}>
      <ScrollView contentContainerStyle={s.scroll} keyboardShouldPersistTaps="handled">
        <Card title="Montre">
          <Row>
            <Dot ok={w.connected} />
            <View style={{ flex: 1 }}>
              <Text style={s.text}>{w.connected ? `Connectée : ${w.name ?? "montre"}` : w.name ? `${w.name} : injoignable` : "Aucune montre Aura"}</Text>
              <Text style={s.dim}>Dernière vue : {relTime(w.lastSeen)}</Text>
              {w.error ? <Text style={[s.dim, { color: theme.warn }]}>{w.error}</Text> : null}
            </View>
          </Row>
          <Row>
            <Button label={pinging ? "Ping…" : "Tester (ping)"} onPress={ping} disabled={pinging} />
            <Button label="Actualiser" kind="ghost" onPress={() => AuraDevice.refreshWatch()} />
          </Row>
          <Text style={s.dim}>
            La montre doit avoir l'app Aura (même clé de signature, dev.aura.mobile) et être reliée via Galaxy Wearable.
          </Text>
        </Card>

        <Card title="Réponses sur la montre">
          {MODES.map((m) => (
            <Pressable key={m.value} onPress={() => update({ ...settings, voice_mode: m.value })} style={{ minHeight: 48, justifyContent: "center" }}
              accessibilityRole="radio" accessibilityState={{ checked: settings.voice_mode === m.value }} accessibilityLabel={`${m.label} : ${m.hint}`}>
              <Row>
                <View style={{ width: 20, height: 20, borderRadius: 10, borderWidth: 2, borderColor: theme.accent,
                  alignItems: "center", justifyContent: "center" }}>
                  {settings.voice_mode === m.value && <View style={{ width: 10, height: 10, borderRadius: 5, backgroundColor: theme.accent }} />}
                </View>
                <View style={{ flex: 1 }}>
                  <Text style={s.text}>{m.label}</Text>
                  <Text style={s.dim}>{m.hint}</Text>
                </View>
              </Row>
            </Pressable>
          ))}
        </Card>

        <Card title="Horaires de travail">
          <View style={{ flexDirection: "row", justifyContent: "space-between" }}>
            {DAYS.map((d, i) => {
              const on = settings.work_hours.days.includes(i + 1)
              return (
                <Pressable key={i} onPress={() => toggleDay(i + 1)} hitSlop={2}
                  accessibilityRole="checkbox" accessibilityState={{ checked: on }} accessibilityLabel={DAY_NAMES[i]}
                  style={{ width: 44, height: 44, borderRadius: 22, alignItems: "center", justifyContent: "center",
                    backgroundColor: on ? theme.accent : "transparent", borderWidth: 1, borderColor: on ? theme.accent : theme.border }}>
                  <Text style={{ color: on ? theme.onAccent : theme.dim, fontWeight: "700", fontSize: 15 }}>{d}</Text>
                </Pressable>
              )
            })}
          </View>
          <Row>
            <Text style={s.text}>De</Text>
            <TextInput value={start} onChangeText={(v) => { setStart(v); setDirty(true) }} style={input} accessibilityLabel="Début de journée" maxLength={5} keyboardType="numbers-and-punctuation" />
            <Text style={s.text}>à</Text>
            <TextInput value={end} onChangeText={(v) => { setEnd(v); setDirty(true) }} style={input} accessibilityLabel="Fin de journée" maxLength={5} keyboardType="numbers-and-punctuation" />
          </Row>
          <Text style={s.dim}>En mode Auto, la montre reste silencieuse sur ces créneaux.</Text>
        </Card>

        <Button label={dirty ? "Enregistrer et envoyer à la montre" : "Renvoyer à la montre"} onPress={save} />
      </ScrollView>
    </SafeAreaView>
  )
}

const input = {
  color: theme.text, borderWidth: 1, borderColor: theme.border, borderRadius: 8, paddingHorizontal: 10, paddingVertical: 6,
  minWidth: 80, minHeight: 48, textAlign: "center" as const, fontSize: 16,
}
