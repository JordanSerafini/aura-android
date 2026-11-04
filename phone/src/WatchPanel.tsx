import { memo, useState } from "react"
import { Alert, Pressable, Text, TextInput, View } from "react-native"
import { TOUCH, font, mono, radius } from "./config"
import { AuraDevice, pingWatch, relTime, type WatchState } from "./native"
import { makeStyles, useTheme } from "./theme"
import { Button, Dot, useUi } from "./ui"

type VoiceMode = "auto" | "voice" | "text"
type WatchSettings = { voice_mode: VoiceMode, work_hours: { days: number[], start: string, end: string } }

const DAYS = ["L", "M", "M", "J", "V", "S", "D"]
const DAY_NAMES = ["lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"]
const MODES: { value: VoiceMode, label: string, hint: string }[] = [
  { value: "auto", label: "Auto", hint: "Lu à voix haute, sauf aux heures de travail (texte + vibration)" },
  { value: "voice", label: "Voix", hint: "Toujours lu à voix haute (voix de synthèse du serveur)" },
  { value: "text", label: "Texte", hint: "Jamais lu : texte sur la montre" },
]
const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/

function load(): WatchSettings {
  try { return JSON.parse(AuraDevice.getWatchSettings()) as WatchSettings } catch {
    return { voice_mode: "auto", work_hours: { days: [1, 2, 3, 4, 5], start: "08:30", end: "17:30" } }
  }
}

const useStyles = makeStyles((t) => ({
  radio: { width: 22, height: 22, borderRadius: 11, borderWidth: 2, borderColor: t.accent, alignItems: "center", justifyContent: "center" },
  radioOn: { width: 10, height: 10, borderRadius: 5, backgroundColor: t.accent },
  // 48 dp de cible : la pastille dessinée fait 44, le hitSlop complète
  day: { minWidth: 44, minHeight: 44, borderRadius: 22, alignItems: "center", justifyContent: "center", borderWidth: 1 },
  time: { color: t.text, borderWidth: 1, borderColor: t.control, borderRadius: radius.sm, paddingHorizontal: 10, paddingVertical: 6, minWidth: 84, minHeight: TOUCH, textAlign: "center", fontSize: font.base, fontFamily: mono },
}))

/** Montre : liaison, façon de rendre les réponses, horaires de travail (envoyés à la montre par le Data Layer). */
export const WatchPanel = memo(function WatchPanel({ watch }: { watch: WatchState }) {
  const t = useTheme()
  const s = useUi()
  const st = useStyles()
  const [settings, setSettings] = useState<WatchSettings>(load)
  const [start, setStart] = useState(settings.work_hours.start)
  const [end, setEnd] = useState(settings.work_hours.end)
  const [pinging, setPinging] = useState(false)
  const [dirty, setDirty] = useState(false)
  // accusé de l'envoi : Play Services a-t-il enregistré les réglages (la montre les prend tout de suite, ou à sa prochaine connexion)
  const [sent, setSent] = useState<{ ok: boolean, text: string } | null>(null)

  const update = (next: WatchSettings) => { setSettings(next); setDirty(true); setSent(null) }
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
    setSettings(next)
    setDirty(false)
    setSent(null)
    AuraDevice.setWatchSettings(JSON.stringify(next)).then(
      (ok) => setSent(ok
        ? { ok: true, text: watch.connected ? "Réglages envoyés à la montre." : "Réglages enregistrés : la montre les recevra à sa prochaine connexion." }
        : { ok: false, text: "Envoi impossible (Google Play Services). Réessaie." }),
      () => setSent({ ok: false, text: "Envoi impossible. Réessaie." }),
    )
  }
  const ping = async () => {
    setPinging(true)
    const r = await pingWatch().catch(() => ({ ok: false, error: "erreur", ms: undefined }))
    setPinging(false)
    Alert.alert(r.ok ? "Montre joignable" : "Pas de réponse", r.ok ? `Aller-retour : ${r.ms} ms` : r.error ?? "")
  }

  return (
    <View style={{ gap: 10 }}>
      <View style={s.row}>
        <Dot ok={watch.connected} />
        <View style={s.grow} accessible>
          <Text style={s.text}>{watch.connected ? `Connectée : ${watch.name ?? "montre"}` : watch.name ? `${watch.name} : injoignable` : "Aucune montre Aura"}</Text>
          <Text style={s.dim}>Dernière vue : {relTime(watch.lastSeen)}</Text>
          {watch.error ? <Text style={[s.dim, { color: t.warn }]}>{watch.error}</Text> : null}
        </View>
      </View>
      <View style={s.row}>
        <View style={s.grow}><Button label="Tester (ping)" busy={pinging} onPress={ping} /></View>
        <View style={s.grow}><Button label="Actualiser" kind="ghost" onPress={() => { AuraDevice.refreshWatch().catch(() => {}) }} /></View>
      </View>
      <Text style={s.dim}>La montre doit avoir l'app Aura (même clé de signature, dev.aura.mobile) et être reliée via Galaxy Wearable.</Text>

      <View style={s.sep} />
      <Text style={s.strong} accessibilityRole="header">Réponses sur la montre</Text>
      <View accessibilityRole="radiogroup">
        {MODES.map((m) => {
          const on = settings.voice_mode === m.value
          return (
            <Pressable key={m.value} onPress={() => update({ ...settings, voice_mode: m.value })} style={[s.row, { minHeight: 56 }]}
              accessibilityRole="radio" accessibilityState={{ checked: on }} accessibilityLabel={`${m.label} : ${m.hint}`}>
              <View style={st.radio}>{on ? <View style={st.radioOn} /> : null}</View>
              <View style={s.grow}>
                <Text style={s.text}>{m.label}</Text>
                <Text style={s.dim}>{m.hint}</Text>
              </View>
            </Pressable>
          )
        })}
      </View>

      <View style={s.sep} />
      <Text style={s.strong} accessibilityRole="header">Horaires de travail</Text>
      <View style={{ flexDirection: "row", justifyContent: "space-between", flexWrap: "wrap", gap: 2 }}>
        {DAYS.map((d, i) => {
          const on = settings.work_hours.days.includes(i + 1)
          return (
            <Pressable key={DAY_NAMES[i]} onPress={() => toggleDay(i + 1)} hitSlop={2}
              accessibilityRole="checkbox" accessibilityState={{ checked: on }} accessibilityLabel={DAY_NAMES[i]}
              style={[st.day, { backgroundColor: on ? t.accent : "transparent", borderColor: on ? t.accent : t.control }]}>
              <Text style={{ color: on ? t.onAccent : t.dim, fontWeight: "700", fontSize: font.md }}>{d}</Text>
            </Pressable>
          )
        })}
      </View>
      <View style={[s.row, { flexWrap: "wrap" }]}>
        <Text style={s.text}>De</Text>
        <TextInput value={start} onChangeText={(v) => { setStart(v); setDirty(true) }} style={st.time} accessibilityLabel="Début de journée, format HH:MM"
          maxLength={5} keyboardType="numbers-and-punctuation" />
        <Text style={s.text}>à</Text>
        <TextInput value={end} onChangeText={(v) => { setEnd(v); setDirty(true) }} style={st.time} accessibilityLabel="Fin de journée, format HH:MM"
          maxLength={5} keyboardType="numbers-and-punctuation" />
      </View>
      <Text style={s.dim}>En mode Auto, la montre reste silencieuse sur ces créneaux.</Text>
      <Button label={dirty ? "Enregistrer et envoyer à la montre" : "Renvoyer à la montre"} onPress={save} />
      {sent ? <Text style={[s.dim, { color: sent.ok ? t.ok : t.warn }]} accessibilityLiveRegion="polite">{sent.text}</Text> : null}
    </View>
  )
})
