import { useState } from "react"
import { ScrollView, Text, View } from "react-native"
import { SafeAreaView } from "react-native-safe-area-context"
import { font } from "../config"
import { AuraDevice } from "../native"
import { ONBOARDING_PREF, RESTRICTED_HOW, STEPS, runStep, stepDone, stepRestricted } from "../permissions"
import { useTheme } from "../theme"
import { usePermissions } from "../usePermissions"
import { Button, Card, Progress, useUi } from "../ui"

/**
 * Assistant de démarrage (ex-carte « Premiers pas » de l'onglet Actions) : une étape à la fois, dans l'ordre qui marche
 * avec les paramètres restreints d'Android. Ouvert seul au premier lancement, ensuite seulement depuis Appareil →
 * Permissions → « Revoir l'assistant ». Les étapes facultatives se passent ; « Terminer » le range pour de bon.
 */
export default function OnboardingScreen({ onClose }: { onClose: () => void }) {
  const t = useTheme()
  const s = useUi()
  const { perms, refresh } = usePermissions(true)
  const [skipped, setSkipped] = useState<string[]>([])
  const pending = STEPS.filter((st) => !stepDone(perms, st) && !skipped.includes(st.id))
  const next = pending[0]
  const done = STEPS.filter((st) => stepDone(perms, st)).length

  const finish = () => {
    AuraDevice.setPref(ONBOARDING_PREF, "1")
    onClose()
  }

  return (
    <SafeAreaView style={s.screen} edges={["top", "left", "right", "bottom"]}>
      <ScrollView contentContainerStyle={s.scroll}>
        <Text style={[s.cardTitle, { fontSize: font.xl }]} accessibilityRole="header">Bienvenue dans Aura</Text>
        <Text style={s.dim}>
          Quelques accès pour qu'Aura joigne ce téléphone et agisse quand tu le demandes. Chacun se change plus tard dans
          Appareil → Permissions.
        </Text>
        <Progress value={done / STEPS.length} />
        <Text style={s.dim} accessibilityLiveRegion="polite">{done} étape{done > 1 ? "s" : ""} sur {STEPS.length}</Text>

        <Card>
          {STEPS.map((st, i) => {
            const ok = stepDone(perms, st)
            const current = st === next
            const skip = skipped.includes(st.id)
            return (
              <View key={st.id} style={[s.row, { alignItems: "flex-start" }]} accessible
                accessibilityLabel={`Étape ${i + 1} : ${st.label}${st.optional ? ", facultative" : ""}, ${ok ? "faite" : skip ? "passée" : current ? "en cours" : "à faire"}`}>
                <Text style={[s.strong, { width: 24, textAlign: "center", color: ok ? t.ok : current ? t.accent : t.dim }]}>{ok ? "✓" : String(i + 1)}</Text>
                <View style={s.grow}>
                  <Text style={[s.text, (ok || skip) && { color: t.dim }, current && { fontWeight: "700" }]}>
                    {st.label}{st.optional ? " (facultatif)" : ""}
                  </Text>
                  {current ? <Text style={s.dim}>{st.hint}</Text> : null}
                </View>
              </View>
            )
          })}
        </Card>

        {next ? (
          <View style={{ gap: 10 }}>
            <Button label={`Étape ${STEPS.indexOf(next) + 1} : ${next.label}`} onPress={() => { runStep(perms, next).then(refresh, refresh) }}
              hint={next.hint} />
            {stepRestricted(next) ? (
              <View style={s.help}>
                <Text style={s.dim}>Grisé ou refusé d'office ? {RESTRICTED_HOW}</Text>
                <Button kind="ghost" label="Débloquer (Infos de l'appli)" onPress={() => AuraDevice.openSettings("app_info")} />
              </View>
            ) : null}
            {next.optional ? <Button kind="ghost" label="Passer cette étape" onPress={() => setSkipped((p) => [...p, next.id])} /> : null}
            <Button kind="ghost" label="Plus tard" onPress={finish} hint="Ferme l'assistant ; il reste dans Appareil → Permissions" />
          </View>
        ) : (
          <View style={{ gap: 10 }}>
            <Text style={[s.strong, { color: t.ok }]} accessibilityLiveRegion="polite">C'est prêt.</Text>
            <Button label="Terminer" kind="ok" onPress={finish} />
          </View>
        )}
      </ScrollView>
    </SafeAreaView>
  )
}
