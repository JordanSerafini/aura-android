import { Ionicons } from "@expo/vector-icons"
import { useEffect, useRef, useState } from "react"
import { Animated, Easing, PermissionsAndroid, Pressable, ScrollView, StyleSheet, Text, View } from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"
import { theme } from "../config"
import { AuraDevice } from "../native"

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
}

const EMPTY: Snapshot = { state: "idle", transcript: "", reply: "", tool: null, error: null, level: -100, bluetooth: false }

const COLORS: Record<TalkState, string> = {
  idle: "#3a4656",
  listening: theme.accent,
  hearing: "#8fb0ff",
  sending: "#a78bfa",
  thinking: "#a78bfa",
  speaking: "#34d399",
  error: theme.bad,
  ended: "#3a4656",
}

function label(s: Snapshot, started: boolean): string {
  switch (s.state) {
    case "idle": return started ? "En pause · touche l'orbe pour reprendre" : "Touche l'orbe pour parler"
    case "listening": return "Je t'écoute…"
    case "hearing": return "Je t'entends…"
    case "sending": return "Transcription…"
    case "thinking": return s.tool ? `Aura · ${s.tool.split(" · ")[0]}` : "Aura réfléchit…"
    case "speaking": return "Aura parle · parle pour l'interrompre"
    case "error": return s.error ?? "Erreur"
    case "ended": return "Conversation terminée"
  }
}

export default function TalkScreen({ onClose, autoStart = false }: { onClose: () => void, autoStart?: boolean }) {
  const insets = useSafeAreaInsets()
  const [snap, setSnap] = useState<Snapshot>(EMPTY)
  const [started, setStarted] = useState(false)
  const [startError, setStartError] = useState<string | null>(null)
  const scale = useRef(new Animated.Value(1)).current
  const breath = useRef(new Animated.Value(0)).current
  const scroll = useRef<ScrollView>(null)

  useEffect(() => {
    const sub = AuraDevice.addListener("onTalk", (e) => {
      let m: Partial<Snapshot>
      try { m = JSON.parse(e.json) as Partial<Snapshot> } catch { return }
      setSnap((prev) => ({ ...prev, ...m }))
    })
    return () => {
      sub.remove()
      AuraDevice.talkStop()
    }
  }, [])

  // respiration lente de l'orbe pendant que l'on attend Aura
  useEffect(() => {
    const loop = Animated.loop(Animated.sequence([
      Animated.timing(breath, { toValue: 1, duration: 900, easing: Easing.inOut(Easing.quad), useNativeDriver: true }),
      Animated.timing(breath, { toValue: 0, duration: 900, easing: Easing.inOut(Easing.quad), useNativeDriver: true }),
    ]))
    loop.start()
    return () => loop.stop()
  }, [breath])

  // l'orbe suit le niveau du micro quand l'utilisateur parle, et la voix d'Aura quand elle parle
  useEffect(() => {
    const live = snap.state === "listening" || snap.state === "hearing"
    const norm = Math.max(0, Math.min(1, (snap.level + 55) / 35))
    const target = live ? 1 + norm * 0.28 : snap.state === "speaking" ? 1.12 : 1
    Animated.spring(scale, { toValue: target, speed: 30, bounciness: 4, useNativeDriver: true }).start()
  }, [snap.level, snap.state, scale])

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

  const hangUp = () => {
    AuraDevice.talkStop()
    onClose()
  }

  const waiting = snap.state === "thinking" || snap.state === "sending"
  const halo = breath.interpolate({ inputRange: [0, 1], outputRange: [waiting ? 0.25 : 0.12, waiting ? 0.6 : 0.22] })
  const color = COLORS[snap.state] ?? theme.accent

  return (
    <View style={[st.root, { paddingTop: insets.top + 12, paddingBottom: insets.bottom + 20 }]}>
      <View style={st.top}>
        <Text style={st.title}>Conversation avec Aura</Text>
        {snap.bluetooth ? (
          <View style={st.badge}><Ionicons name="bluetooth" size={14} color={theme.text} /><Text style={st.badgeText}>Casque</Text></View>
        ) : null}
      </View>

      <View style={st.center}>
        <Pressable onPress={tapOrb} accessibilityRole="button" accessibilityLabel={label(snap, started)} style={st.orbHit}>
          <Animated.View style={[st.halo, { backgroundColor: color, opacity: halo, transform: [{ scale: Animated.multiply(scale, 1.25) }] }]} />
          <Animated.View style={[st.orb, { backgroundColor: color, transform: [{ scale }] }]}>
            <Ionicons
              name={snap.state === "speaking" ? "volume-high" : waiting ? "ellipsis-horizontal" : snap.state === "error" ? "alert" : "mic"}
              size={54}
              color="#fff"
            />
          </Animated.View>
        </Pressable>
        <Text style={[st.status, snap.state === "error" && { color: theme.bad }]}>{startError ?? label(snap, started)}</Text>
      </View>

      <ScrollView ref={scroll} style={st.log} contentContainerStyle={st.logIn}
        onContentSizeChange={() => scroll.current?.scrollToEnd({ animated: true })}>
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

      <View style={st.bottom}>
        <Pressable onPress={hangUp} style={({ pressed }) => [st.hang, pressed && { opacity: 0.75 }]}
          accessibilityRole="button" accessibilityLabel="Raccrocher">
          <Ionicons name="call" size={30} color="#fff" style={{ transform: [{ rotate: "135deg" }] }} />
        </Pressable>
        <Text style={st.hint}>Raccrocher</Text>
      </View>
    </View>
  )
}

const ORB = 150

const st = StyleSheet.create({
  root: { flex: 1, backgroundColor: "#0b0f14", paddingHorizontal: 20 },
  top: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", minHeight: 32 },
  title: { color: theme.dim, fontSize: 15, fontWeight: "600", letterSpacing: 0.3 },
  badge: { flexDirection: "row", alignItems: "center", gap: 4, backgroundColor: theme.card, borderRadius: 12, paddingHorizontal: 10, paddingVertical: 4 },
  badgeText: { color: theme.text, fontSize: 12 },
  center: { alignItems: "center", justifyContent: "center", paddingTop: 40, paddingBottom: 24, gap: 28 },
  orbHit: { width: ORB * 1.6, height: ORB * 1.6, alignItems: "center", justifyContent: "center" },
  halo: { position: "absolute", width: ORB, height: ORB, borderRadius: ORB / 2 },
  orb: { width: ORB, height: ORB, borderRadius: ORB / 2, alignItems: "center", justifyContent: "center", elevation: 8 },
  status: { color: theme.text, fontSize: 17, fontWeight: "600", textAlign: "center", minHeight: 24 },
  log: { flex: 1 },
  logIn: { gap: 10, paddingBottom: 12 },
  bubble: { borderRadius: 14, padding: 12, gap: 4, maxWidth: "92%" },
  me: { alignSelf: "flex-end", backgroundColor: "#1d2a44" },
  aura: { alignSelf: "flex-start", backgroundColor: theme.card, borderColor: theme.border, borderWidth: 1 },
  who: { color: theme.dim, fontSize: 12, fontWeight: "700" },
  text: { color: theme.text, fontSize: 16, lineHeight: 23 },
  bottom: { alignItems: "center", gap: 6, paddingTop: 8 },
  hang: { width: 72, height: 72, borderRadius: 36, backgroundColor: "#e5484d", alignItems: "center", justifyContent: "center", elevation: 4 },
  hint: { color: theme.dim, fontSize: 13 },
})
