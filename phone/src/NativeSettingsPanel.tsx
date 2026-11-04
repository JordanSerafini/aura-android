import { memo, useMemo, useState } from "react"
import { Alert, Text, View } from "react-native"
import { APP_VERSION, BRIDGE_URL, DEVICE_TOKEN } from "./config"
import { bridgeLabel } from "./labels"
import { AuraDevice, type AuraState } from "./native"
import { useTheme, useThemePref } from "./theme"
import { Button, Dot, toneColor, useUi } from "./ui"
import { findZone, parseZones, zoneRows, zoneSummary } from "./zones"

// résultat de setZoneHere / removeZone : JSON texte {ok, error?, message?}
function zoneResult(raw: string): { ok: boolean, message?: string } {
  try {
    const o = JSON.parse(raw) as { ok?: unknown, error?: unknown, message?: unknown }
    return { ok: !!o.ok, message: String(o.message ?? o.error ?? "") || undefined }
  } catch {
    return { ok: false, message: "Réponse illisible" }
  }
}

type Info = { model?: string, android?: string, version?: string, version_code?: number, build?: string }
const THEME_LABELS = { auto: "comme le système", light: "clair", dark: "sombre" } as const

/**
 * Réglages qui n'existent que côté natif : le service (connexion app fermée), les zones (position du téléphone), la
 * version installée. Le reste vit à un seul endroit : permissions et assistant par défaut dans « Permissions », thème
 * dans les Réglages de la PWA (Plus → Réglages → Apparence), que l'app suit.
 */
export const NativeSettingsPanel = memo(function NativeSettingsPanel({ state, onOpenAura }: { state: AuraState, onOpenAura: () => void }) {
  const t = useTheme()
  const s = useUi()
  const [pref] = useThemePref()
  const [zoneBusy, setZoneBusy] = useState<string | null>(null)
  const conn = bridgeLabel(state.bridge)
  const info = useMemo<Info>(() => { try { return JSON.parse(AuraDevice.getInfo()) as Info } catch { return {} } }, [])

  const toggleService = () => {
    if (state.service) {
      Alert.alert("Arrêter le service ?", "Le serveur ne pourra plus joindre ce téléphone tant qu'il n'est pas redémarré.", [
        { text: "Annuler", style: "cancel" },
        { text: "Arrêter", style: "destructive", onPress: () => { AuraDevice.setPref("service_off", "1"); AuraDevice.stopService() } },
      ])
    } else {
      AuraDevice.setPref("service_off", "0")
      AuraDevice.startService()
    }
  }

  // zones du dernier `settings` du bridge : relues à chaque état (le bridge rediffuse settings après un settings_set)
  const zones = useMemo(() => parseZones(AuraDevice.getZones()), [state.settings])

  const saveZone = async (zone: string) => {
    setZoneBusy(zone)
    const r = await AuraDevice.setZoneHere(zone).then(zoneResult, (e: unknown) => ({ ok: false, message: e instanceof Error ? e.message : String(e) }))
    setZoneBusy(null)
    Alert.alert(r.ok ? `Zone « ${zone} » enregistrée` : "Zone non enregistrée", r.ok ? "Position actuelle retenue." : r.message ?? "Localisation indisponible ?")
  }

  // remplacer une zone connue demande confirmation (revue du 17/10 : un appui écrasait l'ancienne position sans rien dire)
  const setZone = (zone: string) => {
    const old = findZone(zones, zone)
    if (!old) { saveZone(zone).catch(() => setZoneBusy(null)); return }
    Alert.alert(`Remplacer la zone « ${old.name} » ?`, `Elle est enregistrée ici : ${zoneSummary(old)}. Ta position actuelle la remplacera (même rayon).`, [
      { text: "Annuler", style: "cancel" },
      { text: "Remplacer", onPress: () => { saveZone(zone).catch(() => setZoneBusy(null)) } },
    ])
  }

  const removeZone = (zone: string) => {
    Alert.alert(`Effacer la zone « ${zone} » ?`, "Aura ne saura plus quand tu y es, et le déclencheur « zone » l'oubliera.", [
      { text: "Annuler", style: "cancel" },
      { text: "Effacer", style: "destructive", onPress: () => {
        setZoneBusy(zone)
        AuraDevice.removeZone(zone).then(zoneResult, (e: unknown) => ({ ok: false, message: e instanceof Error ? e.message : String(e) }))
          .then((r) => {
            setZoneBusy(null)
            if (!r.ok) Alert.alert("Zone non effacée", r.message ?? "Le serveur n'a pas pu être joint.")
          })
          .catch(() => setZoneBusy(null))
      } },
    ])
  }

  return (
    <View style={{ gap: 10 }}>
      <Text style={s.strong} accessibilityRole="header">Connexion</Text>
      <View style={s.row}>
        <Dot tone={conn.tone} />
        <View style={s.grow} accessible accessibilityLabel={`${conn.title}. ${conn.help}`}>
          <Text style={[s.strong, { color: toneColor(t, conn.tone) }]}>{conn.title}</Text>
          {conn.help ? <Text style={s.dim}>{conn.help}</Text> : null}
        </View>
      </View>
      {state.bridgeDetail ? <Text style={s.dim}>{state.bridgeDetail}</Text> : null}
      {!DEVICE_TOKEN && <Text style={[s.dim, { color: t.bad }]}>Build sans jeton appareil : relancer scripts/build.sh</Text>}
      <Button label={state.service ? "Arrêter le service" : "Démarrer le service"} kind={state.service ? "ghost" : "primary"} onPress={toggleService}
        hint="Le service garde la connexion app fermée et redémarre au boot" />
      <Text style={s.dim}>Le service garde la connexion app fermée (notification « Aura connecté ») et redémarre au boot.</Text>
      <Text style={s.small} selectable>{state.device ? `${state.device} · ` : ""}{BRIDGE_URL}</Text>

      <View style={s.sep} />
      <Text style={s.strong} accessibilityRole="header">Mes zones</Text>
      <Text style={s.dim}>Enregistre l'endroit où tu es : Aura adapte ses réponses (discrétion au travail) et le déclencheur « zone » s'en sert.</Text>
      {state.settings === null ? <Text style={s.dim}>Zones enregistrées inconnues tant que le serveur n'a pas répondu.</Text> : null}
      {zoneRows(zones).map(({ name, zone }) => (
        <View key={name} style={{ gap: 6 }}>
          <View accessible accessibilityLabel={zone ? `Zone ${zone.name} : ${zoneSummary(zone)}` : `Zone ${name} : pas encore enregistrée`}>
            <Text style={s.text}>{zone ? zone.name : name}</Text>
            <Text style={s.dim}>{zone ? zoneSummary(zone) : "Pas encore enregistrée"}</Text>
          </View>
          <View style={[s.row, { flexWrap: "wrap" }]}>
            <View style={s.grow}>
              <Button kind="ghost" small label={zone ? "Remplacer par ma position" : "Je suis ici"} busy={zoneBusy === name} disabled={!!zoneBusy}
                a11y={zone ? `Remplacer la zone ${name} par ma position actuelle` : `Enregistrer ma position actuelle comme ${name}`}
                onPress={() => setZone(name)} />
            </View>
            {zone ? (
              <View style={s.grow}>
                <Button kind="ghost" small label="Effacer" disabled={!!zoneBusy} a11y={`Effacer la zone ${zone.name}`} onPress={() => removeZone(zone.name)} />
              </View>
            ) : null}
          </View>
        </View>
      ))}

      <View style={s.sep} />
      <Text style={s.strong} accessibilityRole="header">Apparence</Text>
      <Text style={s.dim}>Thème {THEME_LABELS[pref]}. Il suit le système, ou le choix fait dans l'interface web si elle en propose un.</Text>
      <Button kind="ghost" small label="Ouvrir les Réglages" onPress={onOpenAura} />

      <View style={s.sep} />
      <Text style={s.strong} accessibilityRole="header">Version</Text>
      {/* version de l'APK installée (natif), pas celle du bundle JS : ce que le serveur voit dans le hello */}
      <Text style={s.dim}>Aura {info.version ?? APP_VERSION}{info.version_code ? ` (${info.version_code})` : ""} · dev.aura.mobile</Text>
      {!!info.build && <Text style={s.dim}>Build {info.build}</Text>}
      <Text style={s.dim}>{info.model} · Android {info.android} · Expo SDK 54</Text>
      <Text style={s.dim}>Actions déclarées au serveur : {state.capsSent ? "oui" : "pas encore"}</Text>
    </View>
  )
})
