import { Ionicons } from "@expo/vector-icons"
import { memo, type ReactNode } from "react"
import { ActivityIndicator, Pressable, Switch, Text, View, type LayoutChangeEvent, type SwitchProps } from "react-native"
import { TOUCH, display, font, mono, radius, space, type Theme } from "./config"
import { makeStyles, useTheme } from "./theme"

type Glyph = keyof typeof Ionicons.glyphMap
export type Tone = "ok" | "warn" | "bad" | "dim" | "accent"

export function toneColor(t: Theme, tone: Tone): string {
  return tone === "ok" ? t.ok : tone === "warn" ? t.warn : tone === "bad" ? t.bad : tone === "accent" ? t.accent : t.dim
}

// Styles communs des écrans natifs. Aucune hauteur fixe sur un texte : la taille de police du système (jusqu'à 200 %)
// agrandit les lignes au lieu de les couper.
export const useUi = makeStyles((t) => ({
  screen: { flex: 1, backgroundColor: t.bg },
  scroll: { padding: space.lg, paddingBottom: space.xxl, gap: space.md },
  card: { backgroundColor: t.card, borderRadius: radius.lg, padding: space.lg, borderWidth: 1, borderColor: t.border, gap: space.md },
  cardHead: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: space.sm },
  cardTitle: { color: t.text, fontSize: font.base, fontFamily: display.bold, flexShrink: 1 },
  text: { color: t.text, fontSize: font.base, lineHeight: 22 },
  strong: { color: t.text, fontSize: font.base, lineHeight: 22, fontWeight: "700" },
  dim: { color: t.dim, fontSize: font.md, lineHeight: 20 },
  small: { color: t.dim, fontSize: font.sm, lineHeight: 18 },
  row: { flexDirection: "row", alignItems: "center", gap: space.md },
  grow: { flex: 1 },
  wrap: { flexDirection: "row", flexWrap: "wrap", gap: space.sm },
  sep: { height: 1, backgroundColor: t.border },
  help: { backgroundColor: t.warnSoft, borderColor: t.warnLine, borderWidth: 1, borderRadius: radius.lg, padding: space.lg, gap: space.sm },
  input: { color: t.text, borderColor: t.control, borderWidth: 1, borderRadius: radius.md, paddingHorizontal: space.md, minHeight: TOUCH, fontSize: font.base },
}))

const useParts = makeStyles((t) => ({
  btn: { minHeight: TOUCH, paddingVertical: 10, paddingHorizontal: space.lg, borderRadius: radius.md, alignItems: "center", justifyContent: "center", flexDirection: "row", gap: space.sm },
  btnSmall: { minHeight: 40, paddingVertical: 6, paddingHorizontal: space.md },
  btnText: { fontWeight: "600", fontSize: font.md, textAlign: "center" },
  ghost: { borderWidth: 1, borderColor: t.control },
  choice: { flexGrow: 1, flexBasis: 0, minHeight: TOUCH, paddingHorizontal: space.sm, paddingVertical: 6, borderRadius: radius.md, alignItems: "center", justifyContent: "center", borderWidth: 1 },
  link: { minHeight: 56, flexDirection: "row", alignItems: "center", gap: space.md, paddingVertical: space.sm },
  linkTitle: { color: t.text, fontSize: font.base, fontWeight: "600" },
  badge: { minWidth: 22, minHeight: 22, borderRadius: 11, paddingHorizontal: 6, backgroundColor: t.warn, alignItems: "center", justifyContent: "center" },
  badgeText: { color: t.onWarn, fontSize: font.xs, fontFamily: mono },
  dot: { width: 10, height: 10, borderRadius: 5 },
  progress: { height: 6, borderRadius: 3, backgroundColor: t.border, overflow: "hidden" },
  progressFill: { height: 6, borderRadius: 3 },
  section: { backgroundColor: t.card, borderRadius: radius.lg, borderWidth: 1, borderColor: t.border, overflow: "hidden" },
  sectionHead: { minHeight: 56, flexDirection: "row", alignItems: "center", gap: space.md, paddingHorizontal: space.lg, paddingVertical: space.md },
  sectionBody: { paddingHorizontal: space.lg, paddingBottom: space.lg, gap: space.md },
  message: { alignItems: "center", justifyContent: "center", padding: space.xl, gap: space.md },
  messageTitle: { color: t.text, fontSize: font.xl, fontFamily: display.bold, textAlign: "center" },
  messageText: { color: t.dim, fontSize: font.md, lineHeight: 20, textAlign: "center" },
}))

export function Card({ title, children, right }: { title?: string, children: ReactNode, right?: ReactNode }) {
  const s = useUi()
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

type Kind = "primary" | "ghost" | "danger" | "ok"
function kindColors(t: Theme, kind: Kind): { bg: string, fg: string } {
  if (kind === "ghost") return { bg: "transparent", fg: t.accent }
  if (kind === "danger") return { bg: t.bad, fg: t.onBad }
  if (kind === "ok") return { bg: t.ok, fg: t.onOk }
  return { bg: t.accent, fg: t.onAccent }
}

// Cible tactile : 48 dp (40 + hitSlop de 4 de chaque côté pour « small »). `a11y` remplace le libellé lu, `hint` dit l'effet.
export function Button({ label, onPress, kind = "primary", small, disabled, busy, a11y, hint, glyph }: {
  label: string, onPress: () => void, kind?: Kind, small?: boolean, disabled?: boolean, busy?: boolean, a11y?: string, hint?: string,
  glyph?: Glyph
}) {
  const t = useTheme()
  const p = useParts()
  const k = kindColors(t, kind)
  const off = !!disabled || !!busy
  return (
    <Pressable
      onPress={onPress}
      disabled={off}
      hitSlop={small ? 4 : 0}
      accessibilityRole="button"
      accessibilityLabel={a11y ?? label}
      accessibilityHint={hint}
      accessibilityState={{ disabled: off, busy: !!busy }}
      android_ripple={{ color: t.ripple }}
      style={({ pressed }) => [p.btn, small && p.btnSmall, kind === "ghost" && p.ghost,
        { backgroundColor: k.bg, opacity: disabled ? 0.45 : pressed ? 0.75 : 1 }]}
    >
      {busy ? <ActivityIndicator size="small" color={k.fg} /> : glyph ? <Ionicons name={glyph} size={18} color={k.fg} /> : null}
      <Text style={[p.btnText, small && { fontSize: font.sm }, { color: k.fg }]}>{label}</Text>
    </Pressable>
  )
}

// Choix exclusif (mode, durée, filtre) : 48 dp de haut, la sélection se lit aussi à voix haute (radio)
export function Choice({ label, selected, onPress, a11y }: { label: string, selected: boolean, onPress: () => void, a11y?: string }) {
  const t = useTheme()
  const p = useParts()
  return (
    <Pressable onPress={onPress} accessibilityRole="radio" accessibilityState={{ checked: selected, selected }} accessibilityLabel={a11y ?? label}
      style={[p.choice, { borderColor: selected ? t.accent : t.control, backgroundColor: selected ? t.accent : "transparent" }]}>
      <Text style={{ color: selected ? t.onAccent : t.text, fontWeight: "600", fontSize: font.md, textAlign: "center" }}>{label}</Text>
    </Pressable>
  )
}

// Interrupteur : zone tactile étendue à 48 dp (le dessin natif fait ~32 dp de haut) ; l'état coché est annoncé par Android
export function Toggle(props: SwitchProps) {
  const t = useTheme()
  return (
    <Switch trackColor={{ true: t.accent, false: t.control }} thumbColor={props.value ? t.onAccent : t.dim}
      hitSlop={{ top: 12, bottom: 12, left: 8, right: 8 }} accessibilityRole="switch" {...props} />
  )
}

// Ligne de réglage avec interrupteur : toute la ligne est la cible (libellé + explication lus avec l'état)
export const ToggleRow = memo(function ToggleRow({ label, hint, value, onChange, disabled, hintTone }: {
  label: string, hint?: string, value: boolean, onChange: (v: boolean) => void, disabled?: boolean, hintTone?: Tone
}) {
  const s = useUi()
  const t = useTheme()
  return (
    <Pressable onPress={() => !disabled && onChange(!value)} disabled={disabled} accessibilityRole="switch"
      accessibilityState={{ checked: value, disabled: !!disabled }} accessibilityLabel={label} accessibilityHint={hint}
      style={[s.row, { minHeight: TOUCH }]}>
      <View style={s.grow} importantForAccessibility="no-hide-descendants">
        <Text style={[s.text, disabled && { color: t.dim }]}>{label}</Text>
        {hint ? <Text style={[s.dim, hintTone && { color: toneColor(t, hintTone) }]}>{hint}</Text> : null}
      </View>
      <Toggle value={value} disabled={disabled} onValueChange={onChange} importantForAccessibility="no" accessibilityElementsHidden />
    </Pressable>
  )
})

// Ligne de navigation : titre, une ligne d'explication, chevron, badge facultatif ; toute la largeur est la cible (56 dp)
export function LinkRow({ title, hint, onPress, badge, glyph }: {
  title: string, hint?: string, onPress: () => void, badge?: string | number, glyph?: Glyph
}) {
  const t = useTheme()
  const s = useUi()
  const p = useParts()
  const hasBadge = badge !== undefined && badge !== 0
  return (
    <Pressable onPress={onPress} accessibilityRole="button" accessibilityLabel={[title, hasBadge ? String(badge) : "", hint ?? ""].filter(Boolean).join(". ")}
      android_ripple={{ color: t.ripple }} style={({ pressed }) => [p.link, pressed && { opacity: 0.6 }]}>
      {glyph ? <Ionicons name={glyph} size={22} color={t.accent} /> : null}
      <View style={s.grow}>
        <Text style={p.linkTitle}>{title}</Text>
        {hint ? <Text style={s.dim}>{hint}</Text> : null}
      </View>
      {hasBadge ? <View style={p.badge}><Text style={p.badgeText}>{badge}</Text></View> : null}
      <Ionicons name="chevron-forward" size={18} color={t.dim} />
    </Pressable>
  )
}

/** Pastille d'état : décorative, le texte voisin dit la même chose (jamais la couleur seule). */
export function Dot({ ok, warn, tone }: { ok?: boolean, warn?: boolean, tone?: Tone }) {
  const t = useTheme()
  const p = useParts()
  const color = tone ? toneColor(t, tone) : ok ? t.ok : warn ? t.warn : t.bad
  return <View style={[p.dot, { backgroundColor: color }]} accessibilityElementsHidden importantForAccessibility="no" />
}

export function Row({ children }: { children: ReactNode }) {
  return <View style={useUi().row}>{children}</View>
}

// Barre de progression (permissions prêtes) : visuelle seulement, la valeur est aussi écrite en texte
export function Progress({ value }: { value: number }) {
  const t = useTheme()
  const p = useParts()
  const pct = Math.max(0, Math.min(1, value))
  return (
    <View style={p.progress} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
      <View style={[p.progressFill, { width: `${Math.round(pct * 100)}%`, backgroundColor: pct >= 1 ? t.ok : t.accent }]} />
    </View>
  )
}

/**
 * Section repliable de l'écran Appareil : en-tête de 56 dp (titre, résumé d'une ligne, badge, chevron), état
 * « développé / réduit » annoncé par le lecteur d'écran. Contenu monté seulement une fois ouvert (pas de travail caché).
 */
export function Section({ title, summary, summaryTone, open, onToggle, badge, glyph, children, onLayout }: {
  title: string, summary?: string, summaryTone?: Tone, open: boolean, onToggle: () => void, badge?: number, glyph?: Glyph,
  children: ReactNode, onLayout?: (e: LayoutChangeEvent) => void
}) {
  const t = useTheme()
  const s = useUi()
  const p = useParts()
  return (
    <View style={p.section} onLayout={onLayout}>
      <Pressable onPress={onToggle} accessibilityRole="button" accessibilityState={{ expanded: open }}
        accessibilityLabel={[title, badge ? `${badge} en attente` : "", summary ?? ""].filter(Boolean).join(". ")}
        accessibilityHint={open ? "Réduire la section" : "Afficher la section"}
        android_ripple={{ color: t.ripple }} style={p.sectionHead}>
        {glyph ? <Ionicons name={glyph} size={22} color={t.accent} /> : null}
        <View style={s.grow}>
          <Text style={s.cardTitle} accessibilityRole="header">{title}</Text>
          {summary ? <Text style={[s.dim, summaryTone && { color: toneColor(t, summaryTone) }]} numberOfLines={open ? undefined : 2}>{summary}</Text> : null}
        </View>
        {badge ? <View style={p.badge}><Text style={p.badgeText}>{badge}</Text></View> : null}
        <Ionicons name={open ? "chevron-up" : "chevron-down"} size={20} color={t.dim} />
      </Pressable>
      {open ? <View style={p.sectionBody}>{children}</View> : null}
    </View>
  )
}

/** États pleine zone : chargement, vide, erreur (avec « Réessayer »). */
export function Message({ kind, title, text, onRetry }: { kind: "loading" | "empty" | "error", title?: string, text?: string, onRetry?: () => void }) {
  const t = useTheme()
  const p = useParts()
  return (
    <View style={p.message} accessibilityLiveRegion="polite">
      {kind === "loading" ? <ActivityIndicator color={t.accent} size="large" accessibilityLabel="Chargement" /> : null}
      {kind === "error" ? <Ionicons name="cloud-offline-outline" size={40} color={t.dim} accessibilityElementsHidden importantForAccessibility="no" /> : null}
      {title ? <Text style={p.messageTitle} accessibilityRole="header">{title}</Text> : null}
      {text ? <Text style={p.messageText}>{text}</Text> : null}
      {onRetry ? <Button label="Réessayer" onPress={onRetry} /> : null}
    </View>
  )
}
