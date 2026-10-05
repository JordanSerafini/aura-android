import { useFocusEffect, useNavigation, type NavigationProp, type ParamListBase } from "@react-navigation/native"
import { useCallback, useEffect, useState } from "react"
import { Alert, AppState, PermissionsAndroid, Platform, ScrollView, Text, View, type Permission } from "react-native"
import { SafeAreaView } from "react-native-safe-area-context"
import { theme } from "../config"
import JournalCard from "../JournalCard"
import { AuraDevice, readPermissions, runLocal, useConfirms, type Permissions } from "../native"
import { Button, Card, Dot, Progress, Row, s } from "../ui"

type Group = "essentiel" | "utile" | "avance"
// restricted : bloquee par les « parametres restreints » d'Android 13+ tant qu'on ne les a pas autorises (app hors Play Store)
type PermRow = { key: string, keys?: string[], label: string, hint: string, group: Group, runtime?: string[], special?: string,
  restricted?: boolean }

const P = (name: string) => `android.permission.${name}`
const API = typeof Platform.Version === "number" ? Platform.Version : parseInt(String(Platform.Version), 10)
const BACKGROUND_LOCATION = P("ACCESS_BACKGROUND_LOCATION")

const ROWS: PermRow[] = [
  // Essentiel : sans elles, Aura ne joint pas le telephone ou ne fait pas les actions de tous les jours
  { key: "notifications", group: "essentiel", label: "Notifications", hint: "Confirmations d'actions, connexion en arrière-plan",
    ...(API >= 33 ? { runtime: [P("POST_NOTIFICATIONS")] } : { special: "notifications" }) },
  { key: "microphone", group: "essentiel", label: "Micro", hint: "Messages vocaux dans l'onglet Aura", runtime: [P("RECORD_AUDIO")] },
  { key: "battery_unrestricted", group: "essentiel", label: "Batterie sans restriction", hint: "Rester connecté quand l'app est fermée",
    special: "battery_unrestricted" },
  { key: "contacts", group: "essentiel", label: "Contacts", hint: "Comprendre « appelle Paul », « écris à Camille »", runtime: [P("READ_CONTACTS")] },
  // phone_state : sans elle Android ne livre pas la sonnerie, donc pas de fiche d'appel (le journal le dit)
  { key: "phone", keys: ["phone", "phone_state"], group: "essentiel", label: "Téléphone", hint: "Passer un appel, savoir quand ça sonne",
    runtime: [P("CALL_PHONE"), P("READ_PHONE_STATE")] },
  { key: "sms_send", group: "essentiel", label: "Envoyer des SMS", hint: "Aura rédige, tu confirmes", runtime: [P("SEND_SMS")], restricted: true },
  // Utile : elargit ce qu'Aura sait faire
  { key: "notification_listener", group: "utile", label: "Accès aux notifications", hint: "Lire et répondre (WhatsApp…), musique en cours",
    special: "notification_listener", restricted: true },
  { key: "sms_read", group: "utile", label: "Lire les SMS", hint: "Retrouver un code, un message reçu", runtime: [P("READ_SMS")], restricted: true },
  { key: "call_log", group: "utile", label: "Journal d'appels", hint: "Qui a appelé, rappeler un numéro", runtime: [P("READ_CALL_LOG")], restricted: true },
  { key: "calendar", keys: ["calendar_read", "calendar_write"], group: "utile", label: "Agenda", hint: "Lire et ajouter des rendez-vous",
    runtime: [P("READ_CALENDAR"), P("WRITE_CALENDAR")] },
  { key: "location", group: "utile", label: "Localisation", hint: "« Où suis-je ? », itinéraires", runtime: [P("ACCESS_FINE_LOCATION"), P("ACCESS_COARSE_LOCATION")] },
  { key: "dnd_access", group: "utile", label: "Ne pas déranger", hint: "Mode silencieux à la demande", special: "dnd_access" },
  // app_usage (03/10) : acces special, accorde dans les reglages systeme (« Accès aux données d'utilisation » → Aura)
  { key: "usage_access", group: "utile", label: "Accès à l'utilisation", hint: "Temps d'écran par app (24 h, 7 jours) quand tu le demandes à Aura",
    special: "usage_access" },
  // Avance
  { key: "location_background", group: "avance", label: "Localisation app fermée", hint: "Après « Localisation » : choisir « Toujours autoriser »",
    runtime: [BACKGROUND_LOCATION] },
  { key: "overlay", group: "avance", label: "Afficher par-dessus les autres applis", hint: "Ouvrir WhatsApp, Maps, le réveil quand l'app est fermée",
    special: "overlay" },
  { key: "camera", group: "utile", label: "Caméra", hint: "Photo à la demande (« montre-moi »)", runtime: [P("CAMERA")] },
  { key: "assistant", group: "utile", label: "Assistant par défaut", hint: "Appui long sur le bouton latéral = Aura, avec l'écran en cours",
    special: "assistant" },
  { key: "promoted_notifications", group: "avance", label: "Suivi en direct", hint: "Puce « Aura travaille » dans la barre d'état",
    special: "promoted_notifications" },
  { key: "accessibility", group: "avance", label: "Lecture et contrôle d'écran", hint: "Aura lit l'écran, envoie les WhatsApp confirmés et pilote les apps de ta liste blanche",
    special: "accessibility" },
]

const GROUPS: { id: Group, title: string }[] = [
  { id: "essentiel", title: "Essentiel" },
  { id: "utile", title: "Utile" },
  { id: "avance", title: "Avancé" },
]

// Premiers pas : l'ordre compte. Du plus simple (dialogue Android) au plus long (reglages systeme), et les
// permissions « restreintes » en dernier : Android 13+ n'offre « Autoriser les parametres restreints »
// (Infos de l'appli → ⋮) qu'APRES un premier essai refuse. Cet etat ne se lit pas par API : les etapes
// restreintes portent donc leur propre bouton de deblocage au lieu d'une etape qu'on ne saurait pas cocher.
// unlock : etape facultative qui peut etre grisee par les « parametres restreints » (accessibilite)
type Step = { id: string, label: string, hint: string, rows: string[], unlock?: boolean }
const STEPS: Step[] = [
  { id: "notif", label: "Notifications", hint: "Pour voir les confirmations d'actions.", rows: ["notifications"] },
  { id: "mic", label: "Micro", hint: "Pour les messages vocaux.", rows: ["microphone"] },
  { id: "battery", label: "Batterie sans restriction", hint: "Sinon Android coupe la connexion au serveur.", rows: ["battery_unrestricted"] },
  { id: "people", label: "Contacts et téléphone", hint: "Pour « appelle Paul » et pour savoir quand le téléphone sonne (fiche d'appel).", rows: ["contacts", "phone"] },
  { id: "sms", label: "SMS", hint: "Envoyer (avec ta confirmation) et lire les codes reçus. Refusé d'office : « Débloquer ».",
    rows: ["sms_send", "sms_read"] },
  { id: "listener", label: "Accès aux notifications", hint: "Active Aura dans la liste. Interrupteur grisé : « Débloquer » ci-dessous, puis réessaie.",
    rows: ["notification_listener"] },
  { id: "calls", label: "Journal d'appels", hint: "Pour reconnaître qui t'appelle (fiche client à la sonnerie) et signaler les appels manqués. Refusé d'office : « Débloquer ».",
    rows: ["call_log"] },
  // etapes 8 a 10 (01/10) : facultatives, mais c'est la qu'Aura devient « toujours la » (lecture d'ecran, ouverture d'apps, bouton lateral)
  { id: "a11y", label: "Lecture et contrôle d'écran (facultatif)", hint: "Aura lit l'écran et pilote les apps de ta liste « Contrôle d'apps ». Interrupteur grisé : « Débloquer » ci-dessous, puis réessaie.",
    rows: ["accessibility"], unlock: true },
  { id: "overlay", label: "Affichage par-dessus (facultatif)", hint: "Pour qu'Aura ouvre WhatsApp, Maps ou le réveil quand l'app est fermée.", rows: ["overlay"] },
  { id: "usage", label: "Accès à l'utilisation (facultatif)", hint: "Pour « combien de temps sur mon téléphone aujourd'hui ? ». Active Aura dans la liste « Accès aux données d'utilisation ». Grisé : « Débloquer » ci-dessous.",
    rows: ["usage_access"], unlock: true },
  { id: "assistant", label: "Assistant par défaut (facultatif)", hint: "Appui long sur le bouton latéral = Aura, avec l'écran en cours. Choisis Aura dans « Assistant numérique ».", rows: ["assistant"] },
]
const RESTRICTED_HOW = "Infos de l'appli → menu ⋮ en haut à droite → « Autoriser les paramètres restreints »."

function isGranted(perms: Permissions, row: PermRow): boolean {
  return (row.keys ?? [row.key]).every((k) => perms[k])
}

const byKey = (key: string) => ROWS.find((r) => r.key === key)

export default function ActionsScreen() {
  const [perms, setPerms] = useState<Permissions>(readPermissions)
  const [onboardingHidden, setOnboardingHidden] = useState(() => AuraDevice.getPref("onboarding_done") === "1")
  const confirms = useConfirms()
  const nav = useNavigation<NavigationProp<ParamListBase>>()
  const [, tick] = useState(0)

  const refresh = useCallback(() => {
    setPerms(readPermissions())
    AuraDevice.permissionsChanged()
  }, [])

  useFocusEffect(refresh)
  useEffect(() => {
    const sub = AppState.addEventListener("change", (st) => { if (st === "active") refresh() })
    return () => sub.remove()
  }, [refresh])
  useEffect(() => {
    if (!confirms.length) return
    const t = setInterval(() => tick((n) => n + 1), 1000)
    return () => clearInterval(t)
  }, [confirms.length])

  const requestRuntime = async (list: string[]) => {
    if (!list.length) return
    const res = await PermissionsAndroid.requestMultiple(list as Permission[])
    if (Object.values(res).some((v) => v === "never_ask_again")) {
      Alert.alert("Permission bloquée", "Android ne redemande plus : active-la dans Infos de l'appli → Autorisations. " +
        "Si elle est grisée, autorise d'abord les paramètres restreints (menu ⋮).",
      [{ text: "Plus tard" }, { text: "Ouvrir", onPress: () => AuraDevice.openSettings("app_info") }])
    }
    refresh()
  }

  const grant = async (row: PermRow) => {
    if (row.special) {
      AuraDevice.openSettings(row.special)
      return
    }
    if (row.key === "location_background" && !perms.location) {
      Alert.alert("Localisation d'abord", "Android n'accorde « app fermée » qu'après la localisation normale.")
      return
    }
    await requestRuntime(row.runtime ?? [])
  }

  // un appui : toutes les permissions « classiques » manquantes du groupe, en une suite de dialogues Android.
  // La localisation app fermee reste a part (Android la refuse si elle est demandee avec les autres).
  const grantGroup = async (group: Group) => {
    const missing = ROWS.filter((r) => r.group === group && !isGranted(perms, r) && r.runtime)
    await requestRuntime(missing.flatMap((r) => r.runtime ?? []).filter((p) => p !== BACKGROUND_LOCATION))
  }

  const test = async (action: string, params: Record<string, unknown> = {}) => {
    const r = await runLocal(action, params)
    Alert.alert(r.ok ? `${action} : OK` : `${action} : échec`,
      r.ok ? JSON.stringify(r.result ?? {}, null, 1).slice(0, 1500) : `${r.error}\n${r.message ?? ""}`)
  }

  const ready = ROWS.filter((r) => isGranted(perms, r)).length
  const restrictedMissing = ROWS.some((r) => r.restricted && !isGranted(perms, r))
  const stepDone = (st: Step) => st.rows.every((k) => { const r = byKey(k); return !r || isGranted(perms, r) })
  const stepRestricted = (st: Step) => !!st.unlock || st.rows.some((k) => byKey(k)?.restricted)
  const nextStep = STEPS.find((st) => !stepDone(st))
  const showOnboarding = !onboardingHidden && !!nextStep

  const runStep = async (st: Step) => {
    const rows = st.rows.map(byKey).filter((r): r is PermRow => !!r && !isGranted(perms, r))
    const special = rows.find((r) => r.special)
    if (special?.special) {
      AuraDevice.openSettings(special.special)
      return
    }
    await requestRuntime(rows.flatMap((r) => r.runtime ?? []))
  }

  const hideOnboarding = () => {
    AuraDevice.setPref("onboarding_done", "1")
    setOnboardingHidden(true)
  }

  return (
    <SafeAreaView style={s.screen} edges={["top"]}>
      <ScrollView contentContainerStyle={s.scroll}>
        {confirms.length > 0 && (
          <Card title="Confirmations en attente">
            {confirms.map((c) => (
              <View key={c.action_id} style={{ gap: 8 }}>
                <Text style={s.text}>{c.summary}</Text>
                <Text style={s.dim}>{c.action} · expire dans {Math.max(0, Math.round((c.deadline - Date.now()) / 1000))} s</Text>
                <Row>
                  <Button label="Refuser" kind="danger" onPress={() => AuraDevice.resolveConfirm(c.action_id, false)} />
                  <Button label="Accepter" kind="ok" onPress={() => AuraDevice.resolveConfirm(c.action_id, true)} />
                </Row>
              </View>
            ))}
          </Card>
        )}

        {/* indicateur global : ce qu'on cherche en ouvrant l'ecran */}
        <Card title="Ce qu'Aura peut faire" right={<Text style={[s.dim, ready === ROWS.length && { color: theme.ok }]}>{ready}/{ROWS.length} prêtes</Text>}>
          <Progress value={ready / ROWS.length} />
          <Text style={s.dim}>
            {ready === ROWS.length ? "Tout est accordé." : "Chaque permission ouvre des actions : le serveur ne propose que celles qui sont prêtes."}
          </Text>
        </Card>

        {showOnboarding && nextStep && (
          <Card title="Premiers pas" right={<Button small kind="ghost" label="Masquer" onPress={hideOnboarding} />}>
            {STEPS.map((st, i) => {
              const done = stepDone(st)
              const current = st === nextStep
              return (
                <Row key={st.id}>
                  <Text style={[s.text, { width: 24, textAlign: "center", color: done ? theme.ok : current ? theme.accent : theme.dim }]}>
                    {done ? "✓" : String(i + 1)}
                  </Text>
                  <View style={{ flex: 1 }}>
                    <Text style={[s.text, done && { color: theme.dim }, current && { fontWeight: "700" }]}>{st.label}</Text>
                    {current && <Text style={s.dim}>{st.hint}</Text>}
                  </View>
                </Row>
              )
            })}
            <Button label={`Étape ${STEPS.indexOf(nextStep) + 1} : ${nextStep.label}`} onPress={() => runStep(nextStep)} />
            {stepRestricted(nextStep) && (
              <>
                <Text style={s.dim}>Débloquer : {RESTRICTED_HOW}</Text>
                <Button kind="ghost" label="Débloquer (Infos de l'appli)" onPress={() => AuraDevice.openSettings("app_info")} />
              </>
            )}
          </Card>
        )}

        {!showOnboarding && restrictedMissing && (
          <View style={s.help}>
            <Text style={[s.text, { fontWeight: "700" }]}>Permission grisée ? Paramètres restreints</Text>
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
            <Card key={g.id} title={`${g.title} · ${ok}/${rows.length}`}
              right={batch ? <Button small label="Tout autoriser" onPress={() => grantGroup(g.id)} /> : undefined}>
              {rows.map((row, i) => {
                const granted = isGranted(perms, row)
                return (
                  <View key={row.key} style={{ gap: 10 }}>
                    {i > 0 && <View style={s.sep} />}
                    <Row>
                      <Dot ok={granted} warn={!granted && g.id !== "essentiel"} />
                      <View style={{ flex: 1 }}>
                        <Text style={s.text}>{row.label}</Text>
                        <Text style={s.dim}>{row.hint}</Text>
                      </View>
                      {granted ? <Text style={[s.dim, { color: theme.ok }]}>accordé</Text>
                        : <Button small kind={row.special ? "ghost" : "primary"} label={row.special ? "Ouvrir" : "Autoriser"}
                          a11y={`${row.special ? "Ouvrir le réglage" : "Autoriser"} : ${row.label}`} onPress={() => grant(row)} />}
                    </Row>
                  </View>
                )
              })}
            </Card>
          )
        })}

        <Card title="Tester sur ce téléphone">
          <Text style={s.dim}>Exécute l'action ici, sans passer par le serveur ni demander de confirmation.</Text>
          <Row>
            <Button small kind="ghost" label="État" onPress={() => test("device_status")} />
            <Button small kind="ghost" label="Lampe ON" onPress={() => test("flashlight", { on: true })} />
            <Button small kind="ghost" label="Lampe OFF" onPress={() => test("flashlight", { on: false })} />
          </Row>
          <Row>
            <Button small kind="ghost" label="Position" onPress={() => test("location_get")} />
            <Button small kind="ghost" label="Notifications" onPress={() => test("notif_list", { limit: 5 })} />
            <Button small kind="ghost" label="Faire sonner" onPress={() => test("find_phone")} />
          </Row>
          <Row>
            <Button small kind="ghost" label="Temps d'écran 24 h" onPress={() => test("app_usage", { period: "24h", limit: 10 })} />
            <Button small kind="ghost" label="7 jours" onPress={() => test("app_usage", { period: "7d", limit: 10 })} />
          </Row>
        </Card>

        {/* l'ancien journal « Dernières actions » est le Journal Aura : meme liste, plus les evenements du telephone */}
        <JournalCard limit={3} compact onOpenAll={() => nav.navigate("Réglages", { section: "journal", n: Date.now() })} />
      </ScrollView>
    </SafeAreaView>
  )
}
