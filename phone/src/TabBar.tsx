import { Ionicons } from "@expo/vector-icons"
import type { BottomTabBarProps } from "@react-navigation/bottom-tabs"
import { useEffect, useRef, useState } from "react"
import { AccessibilityInfo, Animated, Keyboard, Pressable, Text, Vibration, View } from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"
import { font, mono, motion } from "./config"
import { makeStyles, useTheme } from "./theme"

type Glyph = keyof typeof Ionicons.glyphMap

// LA MÊME barre que celle de la PWA (écran Aura) : mêmes entrées, même ordre, mêmes icônes. Chat, Notifs, À traiter et
// Plus ramènent sur l'écran Aura à l'ancre voulue (#view=…, #plus : la feuille « Plus » de la PWA, où vivent Appareil,
// Montre, Mode vocal) ; Appareil est donc « dans Plus » et c'est Plus qui s'allume sur son écran. L'assistant de
// démarrage reste sans onglet.
export type TabKey = "chat" | "notifs" | "actions" | "plus"
type Item = { key: TabKey, label: string, on: Glyph, off: Glyph, screen?: string }
const ITEMS: Item[] = [
  { key: "chat", label: "Chat", on: "chatbubble-ellipses", off: "chatbubble-ellipses-outline" },
  { key: "notifs", label: "Notifs", on: "notifications", off: "notifications-outline" },
  { key: "actions", label: "À traiter", on: "file-tray-full", off: "file-tray-full-outline" },
  { key: "plus", label: "Plus", on: "ellipsis-horizontal", off: "ellipsis-horizontal", screen: "Appareil" },
]

export type TabBadge = { value: number | "!" | "9+", label: string }
type Props = BottomTabBarProps & { badges: Partial<Record<TabKey, TabBadge>>, onAura: (hash: string) => void }

const useStyles = makeStyles((t) => ({
  bar: { flexDirection: "row", backgroundColor: t.card, borderTopColor: t.border, borderTopWidth: 1 },
  item: { flex: 1, minHeight: 56, alignItems: "center", justifyContent: "center", paddingVertical: 6, gap: 2 },
  pill: { width: 56, height: 30, borderRadius: 15, alignItems: "center", justifyContent: "center" },
  pillPressed: { transform: [{ scale: 0.9 }] },
  // pastille de l'onglet actif : UN element qui glisse d'un onglet a l'autre (ressort), lisere violet comme la PWA
  glider: { position: "absolute", left: 0, width: 56, height: 30, borderRadius: 15, backgroundColor: t.accentSoft, borderWidth: 1, borderColor: t.glowLine },
  label: { color: t.dim, fontSize: font.xs, fontWeight: "500", textAlign: "center" },
  labelOn: { color: t.text, fontWeight: "700" },
  badge: { position: "absolute", top: -2, right: 4, minWidth: 18, minHeight: 18, borderRadius: 9, paddingHorizontal: 5, backgroundColor: t.bad, alignItems: "center", justifyContent: "center" },
  badgeText: { color: t.onBad, fontSize: font.xs - 1, fontFamily: mono },
}))

// Pastille de compteur : un battement (1 -> 1,24 -> 1) quand le nombre MONTE, comme la PWA (.badge.pop) ; aucun au montage.
function Badge({ badge, st }: { badge: TabBadge, st: ReturnType<typeof useStyles> }) {
  const scale = useRef(new Animated.Value(1)).current
  const prev = useRef(badge.value)
  useEffect(() => {
    const n = (v: TabBadge["value"]) => (typeof v === "number" ? v : v === "9+" ? 10 : 1)
    const up = n(badge.value) > n(prev.current)
    prev.current = badge.value
    if (!up) return
    AccessibilityInfo.isReduceMotionEnabled().then((reduce) => {
      if (reduce) return
      Animated.sequence([
        Animated.timing(scale, { toValue: 1.24, duration: motion.fast, useNativeDriver: true }),
        Animated.spring(scale, { toValue: 1, ...motion.spring, useNativeDriver: true }),
      ]).start()
    }).catch(() => {})
  }, [badge.value, scale])
  return (
    <Animated.View style={[st.badge, { transform: [{ scale }] }]}>
      <Text style={st.badgeText} maxFontSizeMultiplier={1.3}>{badge.value}</Text>
    </Animated.View>
  )
}

// Barre du bas maison des écrans natifs (Appareil) : zone tactile de 56 dp, pastille sous l'icône de l'onglet actif
// (la couleur seule ne suffit pas), libellé qui suit la taille de police du système (bornée à 1,5x, sur 2 lignes au besoin),
// badge lu à voix haute.
export default function TabBar({ state, badges, onAura }: Props) {
  const t = useTheme()
  const st = useStyles()
  const insets = useSafeAreaInsets()
  const [kb, setKb] = useState(false)
  const [itemW, setItemW] = useState(0)
  const [pillY, setPillY] = useState(0)
  const x = useRef(new Animated.Value(0)).current
  const placed = useRef(false)

  useEffect(() => {
    const show = Keyboard.addListener("keyboardDidShow", () => setKb(true))
    const hide = Keyboard.addListener("keyboardDidHide", () => setKb(false))
    return () => { show.remove(); hide.remove() }
  }, [])
  const current = state.routes[state.index]?.name ?? ""
  const activeIndex = ITEMS.findIndex((it) => !!it.screen && current === it.screen)
  // la pastille glisse vers l'onglet actif ; premiere pose (ou mouvement reduit) : directement en place
  useEffect(() => {
    if (activeIndex < 0 || !itemW) return
    const to = activeIndex * itemW + (itemW - 56) / 2
    if (!placed.current) { placed.current = true; x.setValue(to); return }
    AccessibilityInfo.isReduceMotionEnabled().then((reduce) => {
      if (reduce) x.setValue(to)
      else Animated.spring(x, { toValue: to, ...motion.spring, useNativeDriver: true }).start()
    }).catch(() => x.setValue(to))
  }, [activeIndex, itemW, x])
  // Aura : la PWA dessine cette même barre ; deux barres empilées laissaient une zone vide et rendaient les
  // boutons du compositeur inertes (1.3.0). Assistant : plein écran.
  if (kb || current === "Aura" || current === "Assistant") return null

  return (
    <View
      style={[st.bar, { paddingBottom: insets.bottom, paddingLeft: insets.left, paddingRight: insets.right }]}
      accessibilityRole="tablist"
      onLayout={(e) => setItemW((e.nativeEvent.layout.width - insets.left - insets.right) / ITEMS.length)}
    >
      {activeIndex >= 0 && itemW > 0 && pillY > 0 ? (
        <Animated.View pointerEvents="none" style={[st.glider, { top: pillY, transform: [{ translateX: Animated.add(x, insets.left) }] }]} />
      ) : null}
      {ITEMS.map((it, i) => {
        const active = !!it.screen && current === it.screen
        const badge = badges[it.key]
        // comme la PWA : Plus est un bouton (il ouvre une feuille), les trois autres des onglets
        const role = it.key === "plus" ? "button" : "tab"
        const press = () => {
          Vibration.vibrate(8)  // même retour que la PWA (navigator.vibrate(8))
          onAura(it.key === "plus" ? "plus" : `view=${it.key}`)
        }
        return (
          <Pressable
            key={it.key}
            onPress={press}
            accessibilityRole={role}
            accessibilityState={role === "tab" ? { selected: active } : undefined}
            accessibilityLabel={`${it.label}${badge ? `, ${badge.label}` : ""}${role === "tab" ? `, onglet ${i + 1} sur ${ITEMS.length}` : ""}`}
            android_ripple={{ color: t.ripple, borderless: true, radius: 40 }}
            style={st.item}
          >
            {({ pressed }) => (
              <>
                <View
                  style={[st.pill, pressed && st.pillPressed]}
                  onLayout={i === 0 ? (e) => setPillY(e.nativeEvent.layout.y) : undefined}
                >
                  <Ionicons name={active ? it.on : it.off} size={24} color={active ? t.accent : t.dim} />
                  {badge ? <Badge badge={badge} st={st} /> : null}
                </View>
                {/* 2 lignes : à police système 200 %, « À traiter » sur une ligne devenait « À tra… » (revue du 17/10) */}
                <Text style={[st.label, active && st.labelOn]} numberOfLines={2} maxFontSizeMultiplier={1.5}>{it.label}</Text>
              </>
            )}
          </Pressable>
        )
      })}
    </View>
  )
}
