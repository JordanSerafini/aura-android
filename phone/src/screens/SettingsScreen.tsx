import { useRoute } from "@react-navigation/native"
import { useEffect, useRef, useState } from "react"
import { Alert, ScrollView, Text, View } from "react-native"
import { SafeAreaView } from "react-native-safe-area-context"
import { EventsCard, UiControlCard } from "../AutomationCards"
import JournalCard from "../JournalCard"
import PauseCard from "../PauseCard"
import { APP_VERSION, BRIDGE_URL, DEVICE_TOKEN, OUTGOING, theme } from "../config"
import { BRIDGE_LABELS } from "../labels"
import type { Section, SettingsParams } from "../nav"
import { AuraDevice, countUsage, useAuraState } from "../native"
import { Button, Card, Dot, Row, Toggle, s } from "../ui"

// resultat de setZoneHere : JSON texte {ok, error?, message?}
function zoneResult(raw: string): { ok: boolean, message?: string } {
  try {
    const o = JSON.parse(raw) as { ok?: unknown, error?: unknown, message?: unknown }
    return { ok: !!o.ok, message: String(o.message ?? o.error ?? "") || undefined }
  } catch {
    return { ok: false, message: "Réponse illisible" }
  }
}

export default function SettingsScreen() {
  const st = useAuraState()
  const known = st.phoneConfirm !== null
  const confirmMap: Record<string, boolean> = st.phoneConfirm ?? {}
  const [zoneBusy, setZoneBusy] = useState<string | null>(null)
  const conn = BRIDGE_LABELS[st.bridge] ?? { title: st.bridge, help: "", color: theme.dim }

  // « Contrôle d'apps », « Pause d'Aura »… depuis l'accueil Téléphone : on défile jusqu'à la carte (position connue au layout)
  const params = (useRoute().params ?? {}) as NonNullable<SettingsParams>
  const scroll = useRef<ScrollView>(null)
  const ys = useRef<Partial<Record<Section, number>>>({})
  const mark = (k: Section) => ({ onLayout: (e: { nativeEvent: { layout: { y: number } } }) => { ys.current[k] = e.nativeEvent.layout.y } })
  useEffect(() => {
    const section = params.section
    if (!section) return
    if (section === "journal") countUsage("ecran.journal")  // « Journal Aura » ouvert depuis l'accueil Téléphone ou l'onglet Actions
    let tries = 0
    const t = setInterval(() => {
      const y = ys.current[section]
      if (y !== undefined) {
        scroll.current?.scrollTo({ y: Math.max(0, y - 8), animated: true })
        clearInterval(t)
      } else if (++tries > 20) clearInterval(t)
    }, 50)
    return () => clearInterval(t)
  }, [params.section, params.n])

  const setConfirm = (action: string, value: boolean) => {
    const next: Record<string, boolean> = {}
    for (const o of OUTGOING) next[o.action] = confirmMap[o.action] ?? true
    next[action] = value
    AuraDevice.sendBridgeSettings(JSON.stringify({ phone_confirm: next }))
  }

  const toggleService = () => {
    if (st.service) {
      AuraDevice.setPref("service_off", "1")
      AuraDevice.stopService()
    } else {
      AuraDevice.setPref("service_off", "0")
      AuraDevice.startService()
    }
  }

  const setZone = async (zone: "Maison" | "Travail") => {
    setZoneBusy(zone)
    const r = await AuraDevice.setZoneHere(zone).then(zoneResult, (e: unknown) => ({ ok: false, message: e instanceof Error ? e.message : String(e) }))
    setZoneBusy(null)
    Alert.alert(r.ok ? `Zone « ${zone} » enregistrée` : "Zone non enregistrée",
      r.ok ? "Position actuelle retenue." : r.message ?? "Localisation indisponible ?")
  }

  let info: { model?: string, android?: string, version?: string, version_code?: number, build?: string } = {}
  try { info = JSON.parse(AuraDevice.getInfo()) } catch { /* rien */ }

  return (
    <SafeAreaView style={s.screen} edges={["top"]}>
      <ScrollView ref={scroll} contentContainerStyle={s.scroll}>
        <View {...mark("pause")}><PauseCard /></View>

        <View {...mark("ui_control")}><UiControlCard /></View>

        <View {...mark("journal")}><JournalCard /></View>

        <Card title="Connexion">
          <Row>
            <Dot ok={st.bridge === "online"} warn={st.bridge === "connecting"} />
            <View style={{ flex: 1 }} accessible accessibilityLabel={`${conn.title}. ${conn.help}`}>
              <Text style={[s.text, { fontWeight: "700", color: conn.color }]}>{conn.title}</Text>
              {conn.help ? <Text style={s.dim}>{conn.help}</Text> : null}
            </View>
          </Row>
          {st.bridgeDetail ? <Text style={s.dim}>{st.bridgeDetail}</Text> : null}
          {!DEVICE_TOKEN && <Text style={[s.dim, { color: theme.bad }]}>Build sans jeton appareil : relancer scripts/build.sh</Text>}
          <Button label={st.service ? "Arrêter le service" : "Démarrer le service"} kind={st.service ? "ghost" : "primary"} onPress={toggleService} />
          <Text style={s.dim}>
            Le service garde la connexion app fermée (notification « Aura connecté ») et redémarre au boot.
          </Text>
          <Text style={[s.dim, { fontSize: 12 }]} selectable>{st.device ? `${st.device} · ` : ""}{BRIDGE_URL}</Text>
        </Card>

        <Card title="Demander mon accord avant…">
          <Text style={s.dim}>
            Activé : Aura te montre l'action et attend ton « Accepter ». Désactivé : Aura agit sans demander.
          </Text>
          {!known && (
            <Text style={[s.dim, { color: theme.warn }]}>
              {st.bridge === "online" ? "Le serveur n'a pas encore envoyé ses réglages : valeurs par défaut affichées." : "Hors connexion : réglages en lecture seule."}
            </Text>
          )}
          {OUTGOING.map((o) => {
            const on = confirmMap[o.action] ?? true
            return (
              <Row key={o.action}>
                <View style={{ flex: 1 }}>
                  <Text style={s.text}>{o.label}</Text>
                  <Text style={[s.dim, !on && { color: theme.warn }]}>{on ? "Avec confirmation" : "Sans confirmation"}</Text>
                </View>
                <Toggle
                  value={on}
                  onValueChange={(v) => setConfirm(o.action, v)}
                  disabled={st.bridge !== "online"}
                  accessibilityLabel={`Demander mon accord : ${o.label}`}
                />
              </Row>
            )
          })}
        </Card>

        <EventsCard />

        <Card title="Assistant & partage">
          <Text style={s.dim}>Aura depuis n'importe où : bouton latéral, geste d'assistant, menu Partager.</Text>
          <Button kind="ghost" label="Aura comme assistant par défaut" onPress={() => { AuraDevice.openAssistantSettings() }} />
          <Text style={s.dim}>Réglages → Applications par défaut → Assistant numérique : choisir Aura.</Text>
          <View style={s.sep} />
          <Text style={s.text}>Mes zones</Text>
          <Text style={s.dim}>Enregistre l'endroit où tu es : Aura adapte ses réponses (discrétion au travail).</Text>
          <Row>
            <View style={{ flex: 1 }}>
              <Button kind="ghost" label={zoneBusy === "Maison" ? "…" : "Je suis à la maison"} disabled={!!zoneBusy}
                onPress={() => setZone("Maison")} />
            </View>
            <View style={{ flex: 1 }}>
              <Button kind="ghost" label={zoneBusy === "Travail" ? "…" : "Je suis au travail"} disabled={!!zoneBusy}
                onPress={() => setZone("Travail")} />
            </View>
          </Row>
          <View style={s.sep} />
          <Button kind="ghost" label="Accessibilité (lire et piloter l'écran)" onPress={() => { AuraDevice.openSettings("accessibility") }} />
          <Text style={s.dim}>
            Permet à Aura de lire l'écran et de piloter les apps autorisées (Contrôle d'apps) quand tu le demandes. Grisé : paramètres restreints (onglet Actions).
          </Text>
        </Card>

        <Card title="Version">
          {/* version de l'APK installee (natif), pas celle du bundle JS : ce que le serveur voit dans le hello */}
          <Text style={s.dim}>Aura {info.version ?? APP_VERSION}{info.version_code ? ` (${info.version_code})` : ""} · dev.aura.mobile</Text>
          {!!info.build && <Text style={s.dim}>Build {info.build}</Text>}
          <Text style={s.dim}>Expo SDK 54 · React Native 0.81</Text>
          <Text style={s.dim}>{info.model} · Android {info.android}</Text>
          <Text style={s.dim}>Actions déclarées au serveur : {st.capsSent ? "oui" : "pas encore"}</Text>
        </Card>
      </ScrollView>
    </SafeAreaView>
  )
}
