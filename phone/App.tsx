import { createBottomTabNavigator } from "@react-navigation/bottom-tabs"
import { DarkTheme, DefaultTheme, NavigationContainer, createNavigationContainerRef } from "@react-navigation/native"
import { StatusBar } from "expo-status-bar"
import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { AppState, Linking, Modal } from "react-native"
import { SafeAreaProvider } from "react-native-safe-area-context"
import { APP_VERSION, BRIDGE_URL, DEVICE_TOKEN, POSTE } from "./src/config"
import ConfirmSheet from "./src/ConfirmSheet"
import { autoStartNonce, hashOf } from "./src/linkGuards"
import { AuraDevice, countUsage, useConfirms } from "./src/native"
import { ONBOARDING_PREF } from "./src/permissions"
import AuraScreen, { type PwaBadges } from "./src/screens/AuraScreen"
import DeviceScreen, { type DeviceParams, type DeviceSection } from "./src/screens/DeviceScreen"
import OnboardingScreen from "./src/screens/OnboardingScreen"
import TalkScreen from "./src/screens/TalkScreen"
import TabBar, { type TabBadge, type TabKey } from "./src/TabBar"
import { ThemeProvider, useTheme } from "./src/theme"

// Deux écrans (Aura, Appareil) + l'assistant de démarrage, plein écran et sans onglet. Appareil accepte une section : un
// lien profond (aura://appareil/pause) ou la PWA ouvrent directement la bonne. La barre du bas (TabBar) reprend les
// entrées de celle de la PWA : Chat, Notifs, À traiter, Plus.
type Tabs = { Aura: undefined, Appareil: DeviceParams, Assistant: undefined }

const Tab = createBottomTabNavigator<Tabs>()
const nav = createNavigationContainerRef<Tabs>()

// compteurs d'usage (nombres seulement) : un écran « ouvert » = on y arrive, ou l'app revient au premier plan dessus.
// `ecran.telephone`, `ecran.actions` et `ecran.reglages` (écrans) n'existent plus depuis 1.4.0 : `ecran.appareil` et les
// sections (`ecran.permissions`, `ecran.montre`, `ecran.reglages`, `ecran.journal`… à l'ouverture, DeviceScreen).
const SCREEN_COUNTERS: Record<string, string> = {
  Aura: "ecran.aura", Appareil: "ecran.appareil", Assistant: "ecran.assistant",
}
let lastScreen = ""

// onglet visible, pour le Live Update natif (pas de notification par-dessus la réponse déjà affichée)
function reportScreen() {
  const name = nav.getCurrentRoute()?.name ?? ""
  AuraDevice.setScreen(name)
  if (name && name !== lastScreen && SCREEN_COUNTERS[name]) countUsage(SCREEN_COUNTERS[name])
  lastScreen = name
}

const SECTIONS: DeviceSection[] = ["pause", "permissions", "hud", "automatisations", "journal", "montre", "reglages"]

// aura://talk : raccourci du lanceur (appui long sur l'icône) ; aura://talk?autostart=1 : tuile « Dicter à Aura »
// (Réglages rapides), qui démarre l'écoute toute seule. Le mode vocal n'a pas de bouton dans l'en-tête, seulement ces
// deux entrées.
function wantsTalk(url: string | null): boolean {
  return !!url && /^aura:\/\/talk\b/.test(url)
}
// aura://hub : ligne d'alerte du widget « Aura HUD » -> l'écran Aura (le détail des alertes est dans la PWA)
function wantsHub(url: string | null): boolean {
  return !!url && /^aura:\/\/hub\b/.test(url)
}
// Le micro ne demarre SANS GESTE que si le lien porte le jeton a usage unique que la tuile vient d'emettre
// (AuraDevice.consumeTalkNonce, 30 s) : le schema aura:// est ouvrable par n'importe quelle app ou page web,
// un lien sans jeton valide ouvre l'ecran Talk et attend le tap de l'utilisateur
function wantsAutoStart(url: string | null): boolean {
  const nonce = autoStartNonce(url)
  return !!nonce && AuraDevice.consumeTalkNonce(nonce)
}
// aura://appareil[/section] : écran Appareil, section ouverte
function deviceOf(url: string | null): DeviceParams | null {
  const m = url ? /^aura:\/\/appareil(?:\/([a-z]+))?\b/.exec(url) : null
  if (!m) return null
  const section = SECTIONS.find((x) => x === m[1])
  return { section, n: Date.now() }
}
function countBadge(n: number, one: string, many: string): TabBadge | undefined {
  return n > 0 ? { value: n > 9 ? "9+" : n, label: n === 1 ? `1 ${one}` : `${n} ${many}` } : undefined
}

function Root() {
  const theme = useTheme()
  const confirms = useConfirms()
  // pastilles de la PWA (message `badges`) : la barre des écrans natifs montre les mêmes que celle de l'écran Aura
  const [pwaBadges, setPwaBadges] = useState<PwaBadges>({ notifs: 0, backlog: 0, rappels: 0, chat: 0, actions: 0 })
  // widget d'écran d'accueil (17/10 soir) : seulement des pastilles VUES par la PWA (l'état initial à 0 dirait « rien à faire »)
  const onPwaBadges = useCallback((b: PwaBadges) => {
    setPwaBadges(b)
    AuraDevice.setWidgetCounts?.(b.actions, b.notifs)
  }, [])
  const badges = useMemo<Partial<Record<TabKey, TabBadge>>>(() => {
    // « Plus » ne compte que les décisions du Backlog : les confirmations du téléphone et les rappels échus sont déjà dans
    // « À traiter » (groupe « Maintenant », pwaBadges.actions). Avant (revue du 17/10) : « 4 » et « 4 » pour 6 choses distinctes.
    // Même règle que la pastille « Plus » de la PWA.
    const plus = pwaBadges.backlog
    return {
      notifs: countBadge(pwaBadges.notifs, "notification non lue", "notifications non lues"),
      actions: countBadge(pwaBadges.actions, "élément à traiter maintenant", "éléments à traiter maintenant"),
      plus: countBadge(plus, "décision du backlog en attente", "décisions du backlog en attente"),
    }
  }, [pwaBadges])
  const [talk, setTalk] = useState(false)
  // autoStart : l'écran Talk démarre l'écoute dès son ouverture (tuile) ; n change à chaque demande pour le remonter
  const [talkAuto, setTalkAuto] = useState({ auto: false, n: 0 })
  const [openHash, setOpenHash] = useState<{ hash: string, n: number } | null>(null)
  const pendingDevice = useRef<DeviceParams | null>(null)

  const openTalk = useCallback((auto = false) => {
    setTalkAuto((prev) => ({ auto, n: prev.n + 1 }))
    setTalk(true)
    AuraDevice.setScreen("Talk")
    countUsage("ecran.talk")
  }, [])
  const closeTalk = useCallback(() => {
    setTalk(false)
    reportScreen()
  }, [])

  const openDevice = useCallback((params?: DeviceParams) => {
    if (nav.isReady()) nav.navigate("Appareil", params ?? { n: Date.now() })
    else pendingDevice.current = params ?? {}
  }, [])
  // barre du bas des écrans natifs : l'écran Aura, à l'ancre de la PWA (#view=notifs, #plus…)
  const openAuraAt = useCallback((hash: string) => {
    setOpenHash((prev) => ({ hash, n: (prev?.n ?? 0) + 1 }))
    if (nav.isReady()) nav.navigate("Aura")
  }, [])
  const openOnboarding = useCallback(() => { if (nav.isReady()) nav.navigate("Assistant") }, [])
  // fin de l'assistant : retour à l'écran d'avant (Appareil), ou Aura s'il s'est ouvert au premier lancement
  const closeOnboarding = useCallback(() => {
    if (!nav.isReady()) return
    if (nav.canGoBack()) nav.goBack()
    else nav.navigate("Aura")
  }, [])

  useEffect(() => {
    const route = (u: string | null) => {
      if (wantsTalk(u)) openTalk(wantsAutoStart(u))
      const device = deviceOf(u)
      if (device) { setTalk(false); openDevice(device) }
      if (wantsHub(u)) {
        setTalk(false)
        countUsage("widget_hud.hub")
        if (nav.isReady()) nav.navigate("Aura")
      }
      const hash = hashOf(u, (x) => !!AuraDevice.notifLinkOk?.(x))  // F1 : bouton de notification scellé par le téléphone
      if (hash?.startsWith("view=")) countUsage(`raccourci.${hash.slice(5)}`)  // le raccourci sert-il ? (17/10 soir)
      if (hash) {
        setTalk(false)
        setOpenHash((prev) => ({ hash, n: (prev?.n ?? 0) + 1 }))
        if (nav.isReady()) nav.navigate("Aura")
      }
    }
    Linking.getInitialURL().then(route).catch(() => {})
    const sub = Linking.addEventListener("url", ({ url }) => route(url))
    return () => sub.remove()
  }, [openTalk, openDevice])

  // l'app revient au premier plan sur un écran : il compte comme ouvert
  useEffect(() => {
    const sub = AppState.addEventListener("change", (st) => {
      if (st !== "active") return
      const name = nav.getCurrentRoute()?.name
      if (name && SCREEN_COUNTERS[name]) countUsage(SCREEN_COUNTERS[name])
    })
    return () => sub.remove()
  }, [])

  useEffect(() => {
    AuraDevice.configure(BRIDGE_URL, DEVICE_TOKEN, POSTE, APP_VERSION)
    if (AuraDevice.getPref("service_off") !== "1") AuraDevice.startService()
  }, [])

  const onReady = useCallback(() => {
    // premier lancement : l'assistant de démarrage (il remplace les deux dialogues d'avant, micro et notifications,
    // qui en sont les deux premières étapes). Ensuite il ne s'ouvre plus que depuis Appareil → Permissions.
    if (AuraDevice.getPref(ONBOARDING_PREF) !== "1" && AuraDevice.getPref("asked_basics") !== "1") {
      AuraDevice.setPref("asked_basics", "1")
      nav.navigate("Assistant")
    } else if (pendingDevice.current) {
      nav.navigate("Appareil", pendingDevice.current)
    }
    pendingDevice.current = null
    reportScreen()
  }, [])

  const navTheme = useMemo(() => {
    const base = theme.scheme === "dark" ? DarkTheme : DefaultTheme
    return { ...base, colors: { ...base.colors, background: theme.bg, card: theme.card, border: theme.border, primary: theme.accent, text: theme.text, notification: theme.bad } }
  }, [theme])

  useEffect(() => { AuraDevice.setSystemBars?.(theme.scheme === "light", theme.bg) }, [theme])

  return (
    <>
      <StatusBar style={theme.scheme === "dark" ? "light" : "dark"} />
      <NavigationContainer ref={nav} theme={navTheme} onReady={onReady} onStateChange={reportScreen}>
        <Tab.Navigator
          // « history » : le retour Android ramène à l'écran d'où l'on vient, pas toujours à Aura
          backBehavior="history"
          tabBar={(props) => <TabBar {...props} badges={badges} onAura={openAuraAt} />}
          screenOptions={{ headerShown: false, lazy: true }}
        >
          <Tab.Screen name="Aura">
            {() => <AuraScreen onOpenDevice={openDevice} onOpenTalk={() => openTalk(false)}
              onBadges={onPwaBadges} openHash={openHash} badge={confirms.length} />}
          </Tab.Screen>
          <Tab.Screen name="Appareil">{() => <DeviceScreen onOpenOnboarding={openOnboarding} onOpenAura={() => openAuraAt("view=settings")} />}</Tab.Screen>
          <Tab.Screen name="Assistant">{() => <OnboardingScreen onClose={closeOnboarding} />}</Tab.Screen>
        </Tab.Navigator>
      </NavigationContainer>
      <Modal visible={talk} animationType="fade" onRequestClose={closeTalk} statusBarTranslucent navigationBarTranslucent>
        {talk ? <TalkScreen key={talkAuto.n} autoStart={talkAuto.auto} onClose={closeTalk} /> : null}
      </Modal>
      <ConfirmSheet items={confirms} />
    </>
  )
}

export default function App() {
  return (
    <SafeAreaProvider>
      <ThemeProvider>
        <Root />
      </ThemeProvider>
    </SafeAreaProvider>
  )
}
