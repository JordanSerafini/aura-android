import { useRoute } from "@react-navigation/native"
import { memo, useCallback, useEffect, useRef, useState } from "react"
import { ScrollView, Text, View } from "react-native"
import { SafeAreaView } from "react-native-safe-area-context"
import { font } from "../config"
import { ConfirmPolicyPanel, EventsPanel, UiControlPanel } from "../AutomationPanels"
import { JournalPanel } from "../JournalPanel"
import { actionLabel, bridgeLabel } from "../labels"
import { NativeSettingsPanel } from "../NativeSettingsPanel"
import { HudSettingsPanel, hudSummary, useHud } from "../HudPanel"
import { AuraDevice, countUsage, isPaused, useAuraState, useConfirms, useJournal, usePause, type Confirm } from "../native"
import { PausePanel, pauseText } from "../PausePanel"
import { ROWS, isGranted } from "../permissions"
import { PermissionsPanel } from "../PermissionsPanel"
import { useTheme } from "../theme"
import { usePermissions } from "../usePermissions"
import { Button, Card, Dot, Section, toneColor, useUi } from "../ui"
import { WatchPanel } from "../WatchPanel"

// Écran « Appareil » (1.4.0) : remplace Téléphone, Actions, Montre et Réglages natifs (4 écrans, 32 ouvertures sur 313 du
// 01 au 09/10). L'état en un coup d'œil en haut, puis une section repliable par sujet ; un lien profond (notification,
// PWA) ouvre directement la sienne. Chaque réglage vit à un seul endroit ; ce que la PWA sait faire reste dans la PWA.
export type DeviceSection = "pause" | "permissions" | "hud" | "automatisations" | "journal" | "montre" | "reglages"
export type DeviceParams = { section?: DeviceSection, n?: number } | undefined

const COUNTERS: Record<DeviceSection, string> = {
  pause: "ecran.pause", permissions: "ecran.permissions", hud: "ecran.hud", automatisations: "ecran.automatisations",
  journal: "ecran.journal", montre: "ecran.montre", reglages: "ecran.reglages",
}
const CLOSED: Record<DeviceSection, boolean> = { pause: false, permissions: false, hud: false, automatisations: false, journal: false, montre: false, reglages: false }

// Confirmations en attente : le compte à rebours ne fait battre que cette liste, pas tout l'écran
const ConfirmList = memo(function ConfirmList({ items }: { items: Confirm[] }) {
  const s = useUi()
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [])
  return (
    <View style={{ gap: 10 }}>
      {items.map((c) => {
        const left = Math.max(0, Math.round((c.deadline - now) / 1000))
        return (
          <View key={c.action_id} style={{ gap: 8 }}>
            <Text style={s.text}>{c.summary}</Text>
            <Text style={s.dim}>{actionLabel(c.action)} · expire dans {left} s</Text>
            <View style={s.row}>
              <View style={s.grow}><Button label="Refuser" kind="danger" a11y={`Refuser : ${c.summary}`} onPress={() => AuraDevice.resolveConfirm(c.action_id, false)} /></View>
              <View style={s.grow}><Button label="Accepter" kind="ok" a11y={`Accepter : ${c.summary}`} onPress={() => AuraDevice.resolveConfirm(c.action_id, true)} /></View>
            </View>
          </View>
        )
      })}
    </View>
  )
})

export default function DeviceScreen({ onOpenOnboarding, onOpenAura }: { onOpenOnboarding: () => void, onOpenAura: () => void }) {
  const t = useTheme()
  const s = useUi()
  const st = useAuraState()
  const pause = usePause()
  const confirms = useConfirms()
  const journal = useJournal()
  const { perms, refresh } = usePermissions(true)
  const paused = isPaused(pause)
  const conn = bridgeLabel(st.bridge)
  const ready = ROWS.filter((r) => isGranted(perms, r)).length
  const [hud, setHud] = useHud()

  // la pause en cours ouvre sa section à l'arrivée (c'est ce qu'on vient chercher) ; ensuite, l'utilisateur décide
  const [open, setOpen] = useState<Record<DeviceSection, boolean>>(() => ({ ...CLOSED, pause: paused }))
  // compteur à l'ouverture d'une section (hors du setState : un updater peut être rejoué)
  const openRef = useRef(open)
  openRef.current = open
  const toggle = useCallback((k: DeviceSection) => {
    if (!openRef.current[k]) countUsage(COUNTERS[k])
    setOpen((prev) => ({ ...prev, [k]: !prev[k] }))
  }, [])

  // lien profond vers une section : on l'ouvre puis on défile jusqu'à elle (position connue au layout)
  const params = (useRoute().params ?? {}) as NonNullable<DeviceParams>
  const scroll = useRef<ScrollView>(null)
  const ys = useRef<Partial<Record<DeviceSection, number>>>({})
  const mark = (k: DeviceSection) => (e: { nativeEvent: { layout: { y: number } } }) => { ys.current[k] = e.nativeEvent.layout.y }
  useEffect(() => {
    const section = params.section
    if (!section) return
    if (!openRef.current[section]) countUsage(COUNTERS[section])
    setOpen((prev) => ({ ...prev, [section]: true }))
    let tries = 0
    const id = setInterval(() => {
      const y = ys.current[section]
      if (y !== undefined) {
        scroll.current?.scrollTo({ y: Math.max(0, y - 8), animated: true })
        clearInterval(id)
      } else if (++tries > 20) clearInterval(id)
    }, 50)
    return () => clearInterval(id)
  }, [params.section, params.n])

  const watch = st.watch
  return (
    <SafeAreaView style={s.screen} edges={["top", "left", "right"]}>
      <ScrollView ref={scroll} contentContainerStyle={s.scroll} keyboardShouldPersistTaps="handled">
        <Text style={[s.cardTitle, { fontSize: font.xl, paddingHorizontal: 2 }]} accessibilityRole="header">Appareil</Text>

        <Card>
          <View style={s.row}>
            <Dot tone={conn.tone} />
            <View style={s.grow} accessible accessibilityLabel={`${conn.title}. ${conn.help}`}>
              <Text style={[s.strong, { color: toneColor(t, conn.tone) }]}>{conn.title}</Text>
              {st.bridge !== "online" && conn.help ? <Text style={s.dim}>{conn.help}</Text> : null}
            </View>
          </View>
          <View style={s.row}>
            <Dot ok={!paused} warn={paused} />
            <Text style={[s.text, s.grow, paused && { color: t.warn, fontWeight: "700" }]}>{pauseText(pause.mode, pause.until)}</Text>
          </View>
          <View style={s.row}>
            <Dot tone={watch.connected ? "ok" : "dim"} />
            <Text style={[s.text, s.grow]}>{watch.connected ? `Montre reliée : ${watch.name ?? "montre"}` : "Montre non reliée"}</Text>
          </View>
          {confirms.length > 0 ? (
            <>
              <View style={s.sep} />
              <Text style={[s.strong, { color: t.warn }]} accessibilityRole="header">
                {confirms.length === 1 ? "1 confirmation en attente" : `${confirms.length} confirmations en attente`}
              </Text>
              <ConfirmList items={confirms} />
            </>
          ) : null}
          <Text style={s.small}>Cet écran s'ouvre aussi par un appui long sur l'icône d'Aura (raccourci Appareil).</Text>
        </Card>

        <Section title="Pause d'Aura" glyph="pause-circle-outline" open={open.pause} onToggle={() => toggle("pause")}
          summary={pauseText(pause.mode, pause.until)} summaryTone={paused ? "warn" : undefined} onLayout={mark("pause")}>
          <PausePanel pause={pause} />
        </Section>

        <Section title="Permissions" glyph="shield-checkmark-outline" open={open.permissions} onToggle={() => toggle("permissions")}
          summary={`${ready}/${ROWS.length} prêtes`} summaryTone={ready === ROWS.length ? "ok" : undefined} onLayout={mark("permissions")}>
          <PermissionsPanel perms={perms} refresh={refresh} onOpenOnboarding={onOpenOnboarding} />
        </Section>


        <Section title="HUD hors de l'écran d'accueil" glyph="speedometer-outline" open={open.hud} onToggle={() => toggle("hud")}
          summary={hudSummary(hud)} onLayout={mark("hud")}>
          <HudSettingsPanel hud={hud} set={setHud} />
        </Section>

        <Section title="Automatisations" glyph="flash-outline" open={open.automatisations} onToggle={() => toggle("automatisations")}
          summary="Déclencheurs, contrôle d'apps, accord avant d'agir" onLayout={mark("automatisations")}>
          <EventsPanel />
          <View style={s.sep} />
          <UiControlPanel accessible={!!perms.accessibility} paused={paused} />
          <View style={s.sep} />
          <ConfirmPolicyPanel bridge={st.bridge} phoneConfirm={st.phoneConfirm} />
        </Section>

        <Section title="Journal Aura" glyph="list-outline" open={open.journal} onToggle={() => toggle("journal")}
          summary={journal.length ? `${journal.length} entrée${journal.length > 1 ? "s" : ""}, la dernière ${relShort(journal[0]?.ts)}` : "Vide"}
          onLayout={mark("journal")}>
          <JournalPanel entries={journal} />
        </Section>

        <Section title="Montre" glyph="watch-outline" open={open.montre} onToggle={() => toggle("montre")}
          summary={watch.connected ? `Reliée : ${watch.name ?? "montre"}` : "Non reliée"} summaryTone={watch.connected ? "ok" : undefined}
          onLayout={mark("montre")}>
          <WatchPanel watch={watch} />
        </Section>

        <Section title="Réglages du téléphone" glyph="settings-outline" open={open.reglages} onToggle={() => toggle("reglages")}
          summary="Service, zones, apparence, version" onLayout={mark("reglages")}>
          <NativeSettingsPanel state={st} onOpenAura={onOpenAura} />
        </Section>
      </ScrollView>
    </SafeAreaView>
  )
}

function relShort(ts: number | undefined): string {
  if (!ts) return "jamais"
  const m = Math.round((Date.now() - ts) / 60000)
  if (m < 1) return "à l'instant"
  if (m < 60) return `il y a ${m} min`
  if (m < 1440) return `il y a ${Math.round(m / 60)} h`
  return `il y a ${Math.round(m / 1440)} j`
}
