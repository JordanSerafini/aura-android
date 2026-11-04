import { memo, useState } from "react"
import { Alert, Text, View } from "react-native"
import { errorLabel } from "./labels"
import { AuraDevice, runLocal, type Permissions } from "./native"
import { GROUPS, RESTRICTED_HOW, ROWS, grantGroup, grantRow, isGranted, missingAlso, BACKGROUND_LOCATION, type PermRow } from "./permissions"
import { useTheme } from "./theme"
import { Button, Dot, Progress, useUi } from "./ui"

const PermLine = memo(function PermLine({ row, granted, essential, missing, onGrant }: {
  row: PermRow, granted: boolean, essential: boolean, missing: string | null, onGrant: (row: PermRow) => void
}) {
  const t = useTheme()
  const s = useUi()
  // accès principal accordé, second accès manquant (caméra sans « Afficher par-dessus ») : on ouvre le réglage du second
  const opens = !!row.special || !!missing
  const status = granted ? "accordé" : missing ? `${row.label} accordée, manque : ${missing}` : "non accordé"
  return (
    <View style={[s.row, { minHeight: 48 }]}>
      <Dot ok={granted} warn={!granted && !essential} />
      <View style={s.grow} accessible accessibilityLabel={`${row.label}, ${status}. ${row.hint}`}>
        <Text style={s.text}>{row.label}</Text>
        <Text style={s.dim}>{row.hint}</Text>
        {missing ? <Text style={[s.dim, { color: t.warn }]}>Accordée, manque : {missing}</Text> : null}
      </View>
      {granted ? <Text style={[s.dim, { color: t.ok }]} importantForAccessibility="no">accordé</Text>
        : <Button small kind={opens ? "ghost" : "primary"} label={opens ? "Ouvrir" : "Autoriser"}
          a11y={missing ? `Ouvrir le réglage : ${missing}` : `${row.special ? "Ouvrir le réglage" : "Autoriser"} : ${row.label}`} onPress={() => onGrant(row)} />}
    </View>
  )
})

const TESTS: { label: string, action: string, params?: Record<string, unknown> }[] = [
  { label: "État", action: "device_status" },
  { label: "Lampe allumée", action: "flashlight", params: { on: true } },
  { label: "Lampe éteinte", action: "flashlight", params: { on: false } },
  { label: "Position", action: "location_get" },
  { label: "Notifications", action: "notif_list", params: { limit: 5 } },
  { label: "Faire sonner", action: "find_phone" },
  { label: "Temps d'écran 24 h", action: "app_usage", params: { period: "24h", limit: 10 } },
  { label: "Temps d'écran 7 jours", action: "app_usage", params: { period: "7d", limit: 10 } },
]

/** Ce qu'Aura peut faire sur ce téléphone (ex-onglet « Actions ») : accès par groupe, paramètres restreints, essais locaux. */
export const PermissionsPanel = memo(function PermissionsPanel({ perms, refresh, onOpenOnboarding }: {
  perms: Permissions, refresh: () => void, onOpenOnboarding: () => void
}) {
  const s = useUi()
  const [testing, setTesting] = useState<string | null>(null)
  const ready = ROWS.filter((r) => isGranted(perms, r)).length
  const restrictedMissing = ROWS.some((r) => r.restricted && !isGranted(perms, r))
  const grant = (row: PermRow) => { grantRow(perms, row).then(refresh, refresh) }

  const executeTest = async (label: string, action: string, params: Record<string, unknown> = {}) => {
    setTesting(label)
    const r = await runLocal(action, params)
    setTesting(null)
    Alert.alert(r.ok ? `${label} : OK` : `${label} : échec`, r.ok ? JSON.stringify(r.result ?? {}, null, 1).slice(0, 1500) : `${errorLabel(r.error ?? "échec")}\n${r.message ?? ""}`)
  }
  const test = async (label: string, action: string, params: Record<string, unknown> = {}) => {
    if (action !== "find_phone") return executeTest(label, action, params)
    Alert.alert("Faire sonner ce téléphone ?", "Le téléphone émettra une sonnerie. Aura n'enverra rien au serveur.", [
      { text: "Annuler", style: "cancel" },
      { text: "Faire sonner", onPress: () => { executeTest(label, action, params).catch(() => setTesting(null)) } },
    ])
  }

  return (
    <View style={{ gap: 10 }}>
      <Progress value={ready / ROWS.length} />
      <Text style={s.dim}>
        {ready === ROWS.length ? "Tout est accordé." : "Chaque permission ouvre des actions : le serveur ne propose que celles qui sont prêtes."}
      </Text>
      <Button kind="ghost" label="Revoir l'assistant de démarrage" glyph="sparkles-outline" onPress={onOpenOnboarding}
        hint="Les étapes une par une, dans le bon ordre" />

      {restrictedMissing && (
        <View style={s.help}>
          <Text style={s.strong}>Permission grisée ? Paramètres restreints</Text>
          <Text style={s.dim}>
            Aura est installée hors Play Store : Android bloque l'accès aux notifications et certaines permissions SMS / journal
            d'appels. Essaie d'abord de l'activer (refus), puis {RESTRICTED_HOW}
          </Text>
          <Button label="Ouvrir Infos de l'appli" kind="ghost" onPress={() => AuraDevice.openSettings("app_info")} />
        </View>
      )}

      {GROUPS.map((g) => {
        const rows = ROWS.filter((r) => r.group === g.id)
        const ok = rows.filter((r) => isGranted(perms, r)).length
        const batch = rows.some((r) => !isGranted(perms, r) && r.runtime && !r.runtime.includes(BACKGROUND_LOCATION))
        return (
          <View key={g.id} style={{ gap: 8 }}>
            <View style={s.sep} />
            <View style={s.row}>
              <Text style={[s.strong, s.grow]} accessibilityRole="header">{g.title} · {ok}/{rows.length}</Text>
              {batch ? <Button small label="Tout autoriser" a11y={`Tout autoriser : ${g.title}`} onPress={() => { grantGroup(perms, g.id).then(refresh, refresh) }} /> : null}
            </View>
            {rows.map((row) => <PermLine key={row.key} row={row} granted={isGranted(perms, row)} essential={g.id === "essentiel"}
              missing={missingAlso(perms, row)} onGrant={grant} />)}
          </View>
        )
      })}

      <View style={s.sep} />
      <Text style={s.strong} accessibilityRole="header">Tester sur ce téléphone</Text>
      <Text style={s.dim}>Les essais restent sur le téléphone, sans passer par le serveur. « Faire sonner » demande une confirmation.</Text>
      <View style={s.wrap}>
        {TESTS.map((x) => (
          <Button key={x.label} small kind="ghost" label={x.label} busy={testing === x.label} disabled={!!testing && testing !== x.label}
            a11y={`Tester : ${x.label}`} onPress={() => { test(x.label, x.action, x.params).catch(() => setTesting(null)) }} />
        ))}
      </View>
    </View>
  )
})
