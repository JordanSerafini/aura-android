import { Ionicons } from "@expo/vector-icons"
import { useEffect, useRef, useState } from "react"
import { Animated, Easing, PermissionsAndroid, Pressable, ScrollView, Text, View } from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"
import { font, radius, type Theme } from "../config"
import { makeStyles, useTheme } from "../theme"
import { AuraDevice } from "../native"
import { useReduceMotion } from "../useReduceMotion"

// Mode conversation vocale : la boucle (ecoute, detection de fin de parole, envoi, lecture en flux,
// coupure si l'utilisateur reparle) vit dans le module natif (TalkSession.kt) ; cet ecran l'affiche.

type TalkState = "idle" | "listening" | "hearing" | "sending" | "thinking" | "speaking" | "error" | "ended"
type Snapshot = {
  state: TalkState
  transcript: string
  reply: string
  tool: string | null
  error: string | null
  level: number
  bluetooth: boolean
  mute_speaking?: boolean
}

const EMPTY: Snapshot = { state: "idle", transcript: "", reply: "", tool: null, error: null, level: -100, bluetooth: false }
// « Micro coupé pendant qu'Aura parle » : pref lue par TalkSession (BargeInLogic.PREF_MUTE = js_ + cette clé)
const MUTE_PREF = "talk_mute_speaking"

// couleur de l'orbe par état, prise dans le thème (l'icône posée dessus est en onAccent, lisible sur chacune)
function orbColor(t: Theme, state: TalkState): string {
  switch (state) {
    case "listening": case "hearing": return t.accent
    case "sending": case "thinking": return t.violet
    case "speaking": return t.ok
    case "error": return t.bad
    default: return t.dim
  }
}

function label(s: Snapshot, started: boolean): string {
  switch (s.state) {
    case "idle": return started ? "En pause · touche l'orbe pour reprendre" : "Touche l'orbe pour parler"
    case "listening": return "Je t'écoute…"
    case "hearing": return "Je t'entends…"
    case "sending": return "Transcription…"
    case "thinking": return s.tool ? `Aura · ${s.tool.split(" · ")[0]}` : "Aura réfléchit…"
    case "speaking": return s.mute_speaking ? "Aura parle · touche l'orbe pour l'interrompre" : "Aura parle · parle pour l'interrompre"
    case "error": return s.error ?? "Erreur"
    case "ended": return "Conversation terminée"
  }
}

export default function TalkScreen({ onClose, autoStart = false }: { onClose: () => void, autoStart?: boolean }) {
  const theme = useTheme()
  const st = useStyles()
  const insets = useSafeAreaInsets()
  const reduce = useReduceMotion()
  const [snap, setSnap] = useState<Snapshot>(EMPTY)
  const [started, setStarted] = useState(false)
  const [startError, setStartError] = useState<string | null>(null)
  const [muted, setMuted] = useState(() => AuraDevice.getPref(MUTE_PREF) === "1")
  const scale = useRef(new Animated.Value(1)).current
  const breath = useRef(new Animated.Value(0)).current
  const scroll = useRef<ScrollView>(null)
  useEffect(() => {
    const sub = AuraDevice.addListener("onTalk", (e) => {
      let m: Partial<Snapshot>
      try { m = JSON.parse(e.json) as Partial<Snapshot> } catch { return }
      setSnap((prev) => ({ ...prev, ...m }))
      if (typeof m.mute_speaking === "boolean") setMuted(m.mute_speaking)
    })
    return () => {
      sub.remove()
      AuraDevice.talkStop()
    }
  }, [])

  // respiration lente de l'orbe pendant que l'on attend Aura. Mouvement réduit : halo fixe (plus marqué pendant l'attente,
  // cf. `halo`) ; l'état reste lisible par la couleur, l'icône et le texte (revue du 17/10 : la boucle ignorait le réglage)
  useEffect(() => {
    if (reduce === null) return
    if (reduce) { breath.setValue(1); return }
    const loop = Animated.loop(Animated.sequence([
      Animated.timing(breath, { toValue: 1, duration: 900, easing: Easing.inOut(Easing.quad), useNativeDriver: true }),
      Animated.timing(breath, { toValue: 0, duration: 900, easing: Easing.inOut(Easing.quad), useNativeDriver: true }),
    ]))
    loop.start()
    return () => loop.stop()
  }, [breath, reduce])

  // l'orbe suit le niveau du micro quand l'utilisateur parle, et la voix d'Aura quand elle parle ; mouvement réduit : taille fixe
  useEffect(() => {
    if (reduce) { scale.setValue(1); return }
    const live = snap.state === "listening" || snap.state === "hearing"
    const norm = Math.max(0, Math.min(1, (snap.level + 55) / 35))
    const target = live ? 1 + norm * 0.28 : snap.state === "speaking" ? 1.12 : 1
    Animated.spring(scale, { toValue: target, speed: 30, bounciness: 4, useNativeDriver: true }).start()
  }, [snap.level, snap.state, scale, reduce])

  const start = async () => {
    const res = await PermissionsAndroid.request("android.permission.RECORD_AUDIO")
    if (res !== "granted") {
      setStartError("Autorise le micro pour parler à Aura.")
      return
    }
    const err = AuraDevice.talkStart()
    if (err) {
      setStartError(err)
      return
    }
    setStartError(null)
    setStarted(true)
  }

  // tuile « Dicter à Aura » : l'écoute démarre toute seule, mains libres (le micro est demandé s'il manque)
  useEffect(() => {
    if (autoStart) start().catch(() => {})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const tapOrb = () => {
    if (!started || snap.state === "ended") start().catch(() => {})
    else AuraDevice.talkTap()
  }

  // endroit bruyant : plus de coupure à la voix tant qu'Aura répond (on la coupe en touchant l'orbe)
  const toggleMute = () => {
    const next = !muted
    setMuted(next)
    // talkSetMute écrit la pref et l'applique à la session en cours ; setPref seul en repli (module d'avant 1.5.0)
    if (AuraDevice.talkSetMute) AuraDevice.talkSetMute(next)
    else AuraDevice.setPref(MUTE_PREF, next ? "1" : "0")
  }

  const hangUp = () => {
    AuraDevice.talkStop()
    onClose()
  }

  const waiting = snap.state === "thinking" || snap.state === "sending"
  const halo = breath.interpolate({ inputRange: [0, 1], outputRange: [waiting ? 0.25 : 0.12, waiting ? 0.6 : 0.22] })
  const color = orbColor(theme, snap.state)

  return (
    <View style={[st.root, { paddingTop: insets.top + 12, paddingBottom: insets.bottom + 20 }]}>
      <View style={st.top}>
        <Text style={st.title} accessibilityRole="header">Conversation avec Aura</Text>
        {snap.bluetooth ? (
          <View style={st.badge} accessible accessibilityLabel="Casque Bluetooth branché"><Ionicons name="bluetooth" size={14} color={theme.text} /><Text style={st.badgeText}>Casque</Text></View>
        ) : null}
      </View>

      <View style={st.center}>
        <Pressable onPress={tapOrb} accessibilityRole="button" accessibilityLabel={label(snap, started)} style={st.orbHit}>
          <Animated.View style={[st.halo, { backgroundColor: color, opacity: halo, transform: [{ scale: Animated.multiply(scale, 1.25) }] }]} />
          <Animated.View style={[st.orb, { backgroundColor: color, transform: [{ scale }] }]}>
            <Ionicons
              name={snap.state === "speaking" ? "volume-high" : waiting ? "ellipsis-horizontal" : snap.state === "error" ? "alert" : "mic"}
              size={54}
              color={theme.onAccent}
            />
          </Animated.View>
        </Pressable>
        <Text style={[st.status, snap.state === "error" && { color: theme.bad }]} accessibilityLiveRegion="polite">{startError ?? label(snap, started)}</Text>
      </View>

      <ScrollView ref={scroll} style={st.log} contentContainerStyle={st.logIn}
        onContentSizeChange={() => scroll.current?.scrollToEnd({ animated: !reduce })}>
        {snap.transcript ? (
          <View style={[st.bubble, st.me]}>
            <Text style={st.who}>Toi</Text>
            <Text style={st.text}>{snap.transcript}</Text>
          </View>
        ) : null}
        {snap.reply ? (
          <View style={[st.bubble, st.aura]}>
            <Text style={st.who}>Aura</Text>
            <Text style={st.text}>{snap.reply}</Text>
          </View>
        ) : null}
      </ScrollView>

      <Pressable onPress={toggleMute} style={({ pressed }) => [st.mute, muted && st.muteOn, pressed && { opacity: 0.75 }]}
        accessibilityRole="switch" accessibilityState={{ checked: muted }} accessibilityLabel="Micro coupé pendant qu'Aura parle"
        accessibilityHint={muted ? "Activé : touche l'orbe pour interrompre Aura" : "Désactivé : parler interrompt Aura"}>
        <Ionicons name={muted ? "mic-off" : "mic"} size={20} color={muted ? theme.accent : theme.dim} />
        <Text style={[st.muteText, muted && { color: theme.text }]}>Micro coupé pendant qu'Aura parle</Text>
        <Text style={[st.muteState, muted && { color: theme.accent }]} importantForAccessibility="no">{muted ? "oui" : "non"}</Text>
      </Pressable>

      <View style={st.bottom}>
        <View style={st.bottomRow}>
          <View style={st.side}>
            <Pressable onPress={hangUp} style={({ pressed }) => [st.hang, pressed && { opacity: 0.75 }]}
              accessibilityRole="button" accessibilityLabel="Raccrocher">
              <Ionicons name="call" size={30} color={theme.onBad} style={{ transform: [{ rotate: "135deg" }] }} />
            </Pressable>
            <Text style={st.hint}>Raccrocher</Text>
          </View>
        </View>
      </View>
    </View>
  )
}

const ORB = 150

const useStyles = makeStyles((t) => ({
  root: { flex: 1, backgroundColor: t.bg, paddingHorizontal: 20 },
  top: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", minHeight: 32 },
  title: { color: t.dim, fontSize: font.md, fontWeight: "600", letterSpacing: 0.3 },
  badge: { flexDirection: "row", alignItems: "center", gap: 4, backgroundColor: t.card, borderRadius: radius.pill, paddingHorizontal: 12, paddingVertical: 4 },
  badgeText: { color: t.text, fontSize: font.xs },
  center: { alignItems: "center", justifyContent: "center", paddingTop: 40, paddingBottom: 24, gap: 28 },
  orbHit: { width: ORB * 1.6, height: ORB * 1.6, alignItems: "center", justifyContent: "center" },
  halo: { position: "absolute", width: ORB, height: ORB, borderRadius: ORB / 2 },
  orb: { width: ORB, height: ORB, borderRadius: ORB / 2, alignItems: "center", justifyContent: "center", elevation: 8 },
  status: { color: t.text, fontSize: font.lg, fontWeight: "600", textAlign: "center" },
  log: { flex: 1 },
  logIn: { gap: 10, paddingBottom: 12 },
  bubble: { borderRadius: radius.lg, padding: 12, gap: 4, maxWidth: "92%" },
  me: { alignSelf: "flex-end", backgroundColor: t.me },
  aura: { alignSelf: "flex-start", backgroundColor: t.card, borderColor: t.border, borderWidth: 1 },
  who: { color: t.dim, fontSize: font.xs, fontWeight: "700" },
  text: { color: t.text, fontSize: font.base, lineHeight: 23 },
  mute: { minHeight: 48, flexDirection: "row", alignItems: "center", gap: 8, paddingHorizontal: 16, paddingVertical: 8, borderRadius: radius.md,
    borderWidth: 1, borderColor: t.border, backgroundColor: t.card, marginTop: 8 },
  muteOn: { borderColor: t.accent },
  muteText: { flex: 1, color: t.dim, fontSize: font.md },
  muteState: { color: t.dim, fontSize: font.sm, fontWeight: "700" },
  bottom: { alignItems: "center", gap: 6, paddingTop: 8 },
  bottomRow: { flexDirection: "row", alignItems: "flex-start", justifyContent: "center", gap: 40 },
  side: { alignItems: "center", gap: 6 },
  hang: { width: 72, height: 72, borderRadius: 36, backgroundColor: t.bad, alignItems: "center", justifyContent: "center", elevation: 4 },
  hint: { color: t.dim, fontSize: font.sm },
}))
