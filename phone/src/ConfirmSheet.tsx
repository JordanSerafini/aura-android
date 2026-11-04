import { useEffect, useState } from "react"
import { Modal, Text, View } from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"
import { font, radius, space } from "./config"
import { actionLabel } from "./labels"
import { AuraDevice, type Confirm } from "./native"
import { makeStyles } from "./theme"
import { Button } from "./ui"

const useStyles = makeStyles((t) => ({
  backdrop: { flex: 1, justifyContent: "flex-end", backgroundColor: t.scrim },
  sheet: { backgroundColor: t.sheet, borderTopLeftRadius: radius.xl, borderTopRightRadius: radius.xl, padding: space.xl, gap: space.md,
    borderColor: t.border, borderWidth: 1 },
  kicker: { color: t.dim, fontSize: font.sm, fontWeight: "600", textTransform: "uppercase", letterSpacing: 0.5 },
  summary: { color: t.text, fontSize: font.lg, lineHeight: 25, fontWeight: "600" },
  meta: { color: t.dim, fontSize: font.sm },
  row: { flexDirection: "row", gap: space.md, marginTop: space.sm },
  flex: { flex: 1 },
}))

// Confirmation par-dessus n'importe quel écran : avant, l'app basculait sur l'onglet Actions et faisait perdre la
// conversation en cours. La première réponse gagne (notification, montre ou ici) ; retour Android = « Refuser ».
// Refuser en contour, Accepter en plein (revue du 17/10) : deux aplats saturés côte à côte se lisaient du même poids, or
// refuser un SMS n'est pas l'acte dangereux.
export default function ConfirmSheet({ items }: { items: Confirm[] }) {
  const st = useStyles()
  const insets = useSafeAreaInsets()
  const c = items[0]
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    if (!c) return
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [c])
  if (!c) return null
  const left = Math.max(0, Math.round((c.deadline - now) / 1000))
  return (
    <Modal transparent animationType="slide" visible statusBarTranslucent navigationBarTranslucent
      onRequestClose={() => AuraDevice.resolveConfirm(c.action_id, false)}>
      <View style={st.backdrop}>
        <View style={[st.sheet, { paddingBottom: space.xl + insets.bottom }]} accessibilityViewIsModal accessibilityLiveRegion="assertive">
          <Text style={st.kicker} accessibilityRole="header">Aura demande ton accord{items.length > 1 ? ` · 1/${items.length}` : ""}</Text>
          <Text style={st.summary}>{c.summary}</Text>
          <Text style={st.meta}>{actionLabel(c.action)} · expire dans {left} s</Text>
          <View style={st.row}>
            <View style={st.flex}><Button label="Refuser" kind="ghost" a11y={`Refuser : ${c.summary}`} onPress={() => AuraDevice.resolveConfirm(c.action_id, false)} /></View>
            <View style={st.flex}><Button label="Accepter" kind="ok" a11y={`Accepter : ${c.summary}`} onPress={() => AuraDevice.resolveConfirm(c.action_id, true)} /></View>
          </View>
        </View>
      </View>
    </Modal>
  )
}
