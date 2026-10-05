import { ScrollView, Text, View } from "react-native"
import { SafeAreaView } from "react-native-safe-area-context"
import { theme } from "../config"
import { BRIDGE_LABELS } from "../labels"
import type { Section } from "../nav"
import { pauseText } from "../PauseCard"
import { isPaused, useAuraState, useConfirms, usePause } from "../native"
import { Button, Card, Dot, Row, s } from "../ui"

type Go = (tab: "Aura" | "Actions" | "Montre" | "Réglages", section?: Section) => void

// Porte d'entree des ecrans natifs (bouton « Téléphone » de l'onglet Aura) : l'etat en un coup d'oeil, puis un
// raccourci par chose qu'on vient chercher. Avant, le bouton menait a Actions (permissions) alors qu'on y cherchait
// les Reglages ou la carte « Contrôle d'apps » ; deux ecrans portaient le nom « Réglages » (celui de la PWA et celui-ci).
export default function PhoneHomeScreen({ go }: { go: Go }) {
  const st = useAuraState()
  const pause = usePause()
  const confirms = useConfirms()
  const conn = BRIDGE_LABELS[st.bridge] ?? { title: st.bridge, help: "", color: theme.dim }
  const paused = isPaused(pause)

  return (
    <SafeAreaView style={s.screen} edges={["top"]}>
      <ScrollView contentContainerStyle={s.scroll}>
        <Card title="Ce téléphone, côté Aura">
          <Text style={s.dim}>
            Ici : ce que le téléphone fait pour Aura (réglages, contrôle d'apps, journal, permissions, montre). Le chat, les
            rappels et les notes sont dans l'onglet Aura.
          </Text>
          <Row>
            <Dot ok={st.bridge === "online"} warn={st.bridge === "connecting"} />
            <View style={{ flex: 1 }} accessible accessibilityLabel={`${conn.title}. ${conn.help}`}>
              <Text style={[s.text, { fontWeight: "700", color: conn.color }]}>{conn.title}</Text>
            </View>
          </Row>
          <Row>
            <Dot ok={!paused} warn={paused} />
            <Text style={[s.text, { flex: 1, color: paused ? theme.warn : theme.text }]}>{pauseText(pause.mode, pause.until)}</Text>
          </Row>
          <Row>
            <Dot ok={st.watch.connected} warn={!st.watch.connected} />
            <Text style={[s.text, { flex: 1 }]}>{st.watch.connected ? `Montre reliée : ${st.watch.name ?? "montre"}` : "Montre non reliée"}</Text>
          </Row>
          {confirms.length > 0 && (
            <Row>
              <Text style={[s.text, { flex: 1, color: theme.warn, fontWeight: "700" }]}>
                {confirms.length === 1 ? "1 confirmation en attente" : `${confirms.length} confirmations en attente`}
              </Text>
              <Button small label="Voir" onPress={() => go("Actions")} />
            </Row>
          )}
        </Card>

        <Card title="Aller à">
          <Button label="⚙  Réglages du téléphone" onPress={() => go("Réglages")} />
          <Button label="⏸  Pause d'Aura" kind="ghost" onPress={() => go("Réglages", "pause")} />
          <Button label="Contrôle d'apps" kind="ghost" onPress={() => go("Réglages", "ui_control")} />
          <Button label="Journal Aura (événements et actions)" kind="ghost" onPress={() => go("Réglages", "journal")} />
          <Button label="Permissions et tests" kind="ghost" onPress={() => go("Actions")} />
          <Button label="Montre" kind="ghost" onPress={() => go("Montre")} />
          <Button label="Retour à Aura" kind="ghost" onPress={() => go("Aura")} />
        </Card>
      </ScrollView>
    </SafeAreaView>
  )
}
