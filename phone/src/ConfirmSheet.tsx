import { useEffect, useState } from "react"
import { Modal, StyleSheet, Text, View } from "react-native"
import { theme } from "./config"
import { AuraDevice, type Confirm } from "./native"
import { Button } from "./ui"

// Confirmation par-dessus n'importe quel ecran : avant, l'app basculait sur l'onglet Actions et faisait
// perdre la conversation en cours. La premiere reponse gagne (notification, montre ou ici).
export default function ConfirmSheet({ items }: { items: Confirm[] }) {
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
    <Modal transparent animationType="slide" visible onRequestClose={() => AuraDevice.resolveConfirm(c.action_id, false)}>
      <View style={st.backdrop}>
        <View style={st.sheet}>
          <Text style={st.kicker}>Aura demande ton accord{items.length > 1 ? ` · 1/${items.length}` : ""}</Text>
          <Text style={st.summary}>{c.summary}</Text>
          <Text style={st.meta}>{c.action} · expire dans {left} s</Text>
          <View style={st.row}>
            <View style={st.flex}><Button label="Refuser" kind="danger" onPress={() => AuraDevice.resolveConfirm(c.action_id, false)} /></View>
            <View style={st.flex}><Button label="Accepter" kind="ok" onPress={() => AuraDevice.resolveConfirm(c.action_id, true)} /></View>
          </View>
        </View>
      </View>
    </Modal>
  )
}

const st = StyleSheet.create({
  backdrop: { flex: 1, justifyContent: "flex-end", backgroundColor: "rgba(0,0,0,0.45)" },
  sheet: { backgroundColor: theme.card, borderTopLeftRadius: 20, borderTopRightRadius: 20, padding: 20, paddingBottom: 36, gap: 10,
    borderColor: theme.border, borderWidth: 1 },
  kicker: { color: theme.dim, fontSize: 13, fontWeight: "600", textTransform: "uppercase", letterSpacing: 0.5 },
  summary: { color: theme.text, fontSize: 18, lineHeight: 25, fontWeight: "600" },
  meta: { color: theme.dim, fontSize: 13 },
  row: { flexDirection: "row", gap: 12, marginTop: 8 },
  flex: { flex: 1 },
})
