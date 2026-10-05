import type { ReactNode } from "react"
import { Pressable, StyleSheet, Switch, Text, View, type SwitchProps } from "react-native"
import { theme } from "./config"

export function Card({ title, children, right }: { title?: string, children: ReactNode, right?: ReactNode }) {
  return (
    <View style={s.card}>
      {title ? (
        <View style={s.cardHead}>
          <Text style={s.cardTitle} accessibilityRole="header">{title}</Text>
          {right}
        </View>
      ) : null}
      {children}
    </View>
  )
}

const KINDS = {
  primary: { bg: theme.accent, fg: theme.onAccent },
  ghost: { bg: "transparent", fg: theme.accent },
  danger: { bg: theme.bad, fg: theme.onBad },
  ok: { bg: theme.ok, fg: theme.onOk },
}

// Cible tactile : 48 px de haut (40 px + hitSlop de 4 de chaque cote pour « small »), Material demande 48 dp
export function Button({ label, onPress, kind = "primary", small, disabled, a11y }: {
  label: string, onPress: () => void, kind?: "primary" | "ghost" | "danger" | "ok", small?: boolean, disabled?: boolean,
  a11y?: string
}) {
  const k = KINDS[kind]
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      hitSlop={small ? 4 : 0}
      accessibilityRole="button"
      accessibilityLabel={a11y ?? label}
      accessibilityState={{ disabled: !!disabled }}
      style={({ pressed }) => [s.btn, small && s.btnSmall, { backgroundColor: k.bg, opacity: disabled ? 0.4 : pressed ? 0.7 : 1 },
        kind === "ghost" && { borderWidth: 1, borderColor: theme.border }]}
    >
      <Text style={[s.btnText, small && { fontSize: 13 }, { color: k.fg }]}>{label}</Text>
    </Pressable>
  )
}

// Choix exclusif (mode, duree, filtre) : cible de 48 px de haut, la selection se lit aussi a voix haute (radio)
export function Choice({ label, selected, onPress, a11y }: { label: string, selected: boolean, onPress: () => void, a11y?: string }) {
  return (
    <Pressable onPress={onPress} accessibilityRole="radio" accessibilityState={{ checked: selected }} accessibilityLabel={a11y ?? label}
      style={{ flexGrow: 1, flexBasis: 0, minHeight: 48, paddingHorizontal: 8, borderRadius: 10, alignItems: "center", justifyContent: "center",
        borderWidth: 1, borderColor: selected ? theme.accent : theme.border, backgroundColor: selected ? theme.accent : "transparent" }}>
      <Text style={{ color: selected ? theme.onAccent : theme.text, fontWeight: "600", fontSize: 14, textAlign: "center" }}>{label}</Text>
    </Pressable>
  )
}

// Interrupteur : zone tactile etendue a 48 dp (le dessin natif fait ~32 dp de haut)
export function Toggle(props: SwitchProps) {
  return <Switch trackColor={{ true: theme.accent, false: theme.border }} hitSlop={{ top: 12, bottom: 12, left: 8, right: 8 }} {...props} />
}

export function Dot({ ok, warn }: { ok: boolean, warn?: boolean }) {
  return <View style={[s.dot, { backgroundColor: ok ? theme.ok : warn ? theme.warn : theme.bad }]} />
}

export function Row({ children }: { children: ReactNode }) {
  return <View style={s.row}>{children}</View>
}

// Barre de progression (permissions pretes) : visuelle seulement, la valeur est aussi ecrite en texte
export function Progress({ value }: { value: number }) {
  const pct = Math.max(0, Math.min(1, value))
  return (
    <View style={s.progress} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
      <View style={[s.progressFill, { width: `${Math.round(pct * 100)}%`, backgroundColor: pct >= 1 ? theme.ok : theme.accent }]} />
    </View>
  )
}

export const s = StyleSheet.create({
  screen: { flex: 1, backgroundColor: theme.bg },
  scroll: { padding: 14, paddingBottom: 40, gap: 12 },
  card: { backgroundColor: theme.card, borderRadius: 14, padding: 14, borderWidth: 1, borderColor: theme.border, gap: 10 },
  cardHead: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 8 },
  cardTitle: { color: theme.text, fontSize: 16, fontWeight: "700", flexShrink: 1 },
  text: { color: theme.text, fontSize: 15, lineHeight: 21 },
  dim: { color: theme.dim, fontSize: 13, lineHeight: 18 },
  row: { flexDirection: "row", alignItems: "center", gap: 10 },
  btn: { minHeight: 48, paddingVertical: 10, paddingHorizontal: 16, borderRadius: 10, alignItems: "center", justifyContent: "center" },
  btnSmall: { minHeight: 40, paddingVertical: 6, paddingHorizontal: 12 },
  btnText: { color: theme.onAccent, fontWeight: "600", fontSize: 14 },
  dot: { width: 10, height: 10, borderRadius: 5 },
  sep: { height: 1, backgroundColor: theme.border },
  help: { backgroundColor: "#2a2113", borderColor: "#5c4318", borderWidth: 1, borderRadius: 14, padding: 14, gap: 8 },
  progress: { height: 6, borderRadius: 3, backgroundColor: theme.border, overflow: "hidden" },
  progressFill: { height: 6, borderRadius: 3 },
})
