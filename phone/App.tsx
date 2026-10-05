import { Ionicons } from "@expo/vector-icons"
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs"
import { DarkTheme, NavigationContainer, createNavigationContainerRef } from "@react-navigation/native"
import { StatusBar } from "expo-status-bar"
import { useCallback, useEffect, useState } from "react"
import { AppState, Linking, Modal, PermissionsAndroid, Platform, type Permission } from "react-native"
import { SafeAreaProvider } from "react-native-safe-area-context"
import { APP_VERSION, BRIDGE_URL, DEVICE_TOKEN, POSTE, theme } from "./src/config"
import { AuraDevice, countUsage, useConfirms } from "./src/native"
import ActionsScreen from "./src/screens/ActionsScreen"
import PhoneHomeScreen from "./src/screens/PhoneHomeScreen"
import type { SettingsParams } from "./src/nav"
import AuraScreen from "./src/screens/AuraScreen"
import ConfirmSheet from "./src/ConfirmSheet"
import SettingsScreen from "./src/screens/SettingsScreen"
import TalkScreen from "./src/screens/TalkScreen"
import WatchScreen from "./src/screens/WatchScreen"

// Réglages accepte une section : « Contrôle d'apps » ouvre Réglages en défilant jusqu'à sa carte (nav.ts)
type Tabs = { Aura: undefined, Téléphone: undefined, Actions: undefined, Montre: undefined, Réglages: SettingsParams }

const Tab = createBottomTabNavigator<Tabs>()
const nav = createNavigationContainerRef<Tabs>()

const ICONS: Record<keyof Tabs, keyof typeof Ionicons.glyphMap> = {
  Aura: "chatbubble-ellipses",
  Téléphone: "phone-portrait",
  Actions: "flash",
  Montre: "watch",
  Réglages: "settings",
}

const navTheme = {
  ...DarkTheme,
  colors: { ...DarkTheme.colors, background: theme.bg, card: theme.card, border: theme.border, primary: theme.accent, text: theme.text },
}

async function firstLaunch() {
  if (AuraDevice.getPref("asked_basics") === "1") return
  AuraDevice.setPref("asked_basics", "1")
  const wanted = ["android.permission.RECORD_AUDIO"]
  if (Platform.OS === "android" && Number(Platform.Version) >= 33) wanted.push("android.permission.POST_NOTIFICATIONS")
  await PermissionsAndroid.requestMultiple(wanted as Permission[])
  AuraDevice.permissionsChanged()
}

// compteurs d'usage (nombres seulement) : un écran « ouvert » = on y arrive, ou l'app revient au premier plan dessus
const SCREEN_COUNTERS: Record<string, string> = {
  Aura: "ecran.aura", Téléphone: "ecran.telephone", Actions: "ecran.actions", Montre: "ecran.montre", Réglages: "ecran.reglages",
}
let lastScreen = ""

// onglet visible, pour le Live Update natif (pas de notification par-dessus la reponse deja affichee)
function reportScreen() {
  const name = nav.getCurrentRoute()?.name ?? ""
  AuraDevice.setScreen(name)
  if (name && name !== lastScreen && SCREEN_COUNTERS[name]) countUsage(SCREEN_COUNTERS[name])
  lastScreen = name
}

// aura://talk : raccourci du lanceur (appui long sur l'icone) ; aura://talk?autostart=1 : tuile « Dicter à Aura »
// (Réglages rapides), qui démarre l'écoute toute seule ; aura://conv/<id> : partage et assistant
// (« ouvrir dans Aura ») menent a la conversation, via l'ancre #conv=<id> de la PWA
function wantsTalk(url: string | null): boolean {
  return !!url && /^aura:\/\/talk\b/.test(url)
}
function wantsAutoStart(url: string | null): boolean {
  return !!url && /^aura:\/\/talk\?(?:.*&)?autostart=1\b/.test(url)
}
// lien profond -> ancre de la PWA : aura://conv/<id> (partage, assistant) ; aura://notifs et
// aura://notif/<id>/act|ask (boutons des notifications Android d'Aura, Notifs.kt)
function hashOf(url: string | null): string | null {
  if (!url) return null
  const conv = /^aura:\/\/conv\/([A-Za-z0-9_-]{1,64})/.exec(url)
  if (conv) return `conv=${conv[1]}`
  const notif = /^aura:\/\/notif\/([A-Za-z0-9_-]{1,64})\/(act|ask)\b/.exec(url)
  if (notif) return `notif-${notif[2]}=${notif[1]}`
  return /^aura:\/\/notifs\b/.test(url) ? "notifs" : null
}

export default function App() {
  const confirms = useConfirms()
  const [talk, setTalk] = useState(false)
  // autoStart : l'écran Talk démarre l'écoute dès son ouverture (tuile) ; n change à chaque demande pour le remonter
  const [talkAuto, setTalkAuto] = useState({ auto: false, n: 0 })
  const [openHash, setOpenHash] = useState<{ hash: string, n: number } | null>(null)

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

  useEffect(() => {
    const route = (u: string | null) => {
      if (wantsTalk(u)) openTalk(wantsAutoStart(u))
      const hash = hashOf(u)
      if (hash) {
        setTalk(false)
        setOpenHash((prev) => ({ hash, n: (prev?.n ?? 0) + 1 }))
        if (nav.isReady()) nav.navigate("Aura")
      }
    }
    Linking.getInitialURL().then(route).catch(() => {})
    const sub = Linking.addEventListener("url", ({ url }) => route(url))
    return () => sub.remove()
  }, [openTalk])

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
    firstLaunch().catch(() => {})
  }, [])

  return (
    <SafeAreaProvider>
      <StatusBar style="light" />
      <NavigationContainer ref={nav} theme={navTheme} onReady={reportScreen} onStateChange={reportScreen}>
        <Tab.Navigator
          screenOptions={({ route }) => ({
            headerShown: false,
            tabBarActiveTintColor: theme.accent,
            tabBarInactiveTintColor: theme.dim,
            // onglet Aura : la PWA a deja sa propre barre (Chat, Rappels, Notes…). Deux barres empilees
            // mangeaient 60 px et la marge basse de la PWA laissait une bande vide au-dessus de la barre
            // native (26/09). On y accede aux ecrans natifs par les boutons 📱 (Téléphone) et ⚙ (Réglages du téléphone) injectes dans son en-tete.
            tabBarStyle: route.name === "Aura" ? { display: "none" } : { backgroundColor: theme.card, borderTopColor: theme.border },
            tabBarHideOnKeyboard: true,
            tabBarIcon: ({ color, size }) => <Ionicons name={ICONS[route.name]} color={color} size={size} />,
            tabBarBadge: route.name === "Actions" && confirms.length ? confirms.length : undefined,
            tabBarLabelStyle: { fontSize: 11 },
            lazy: true,
          })}
        >
          <Tab.Screen name="Aura">
            {() => <AuraScreen onOpenPhone={() => nav.navigate("Téléphone")} onOpenSettings={() => nav.navigate("Réglages")}
              onOpenTalk={() => openTalk()} openHash={openHash} badge={confirms.length} />}
          </Tab.Screen>
          <Tab.Screen name="Téléphone">
            {() => <PhoneHomeScreen go={(tab, section) => { if (tab === "Réglages") nav.navigate("Réglages", { section, n: Date.now() }); else nav.navigate(tab) }} />}
          </Tab.Screen>
          <Tab.Screen name="Actions" component={ActionsScreen} />
          <Tab.Screen name="Montre" component={WatchScreen} />
          <Tab.Screen name="Réglages" component={SettingsScreen} />
        </Tab.Navigator>
      </NavigationContainer>
      <Modal visible={talk} animationType="fade" onRequestClose={closeTalk} statusBarTranslucent navigationBarTranslucent>
        {talk ? <TalkScreen key={talkAuto.n} autoStart={talkAuto.auto} onClose={closeTalk} /> : null}
      </Modal>
      <ConfirmSheet items={confirms} />
    </SafeAreaProvider>
  )
}
