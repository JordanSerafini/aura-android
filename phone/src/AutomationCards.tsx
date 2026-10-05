import { useEffect, useState } from "react"
import { Text, TextInput, View } from "react-native"
import { theme } from "./config"
import {
  AuraDevice, isPaused, listApps, readEventSettings, readUiWhitelist, saveEventSettings, saveUiWhitelist, usePause,
  type AppEntry, type EventSettings,
} from "./native"
import { usePermissions } from "./usePermissions"
import { Button, Card, Row, Toggle, s } from "./ui"

// Selecteur d'apps : liste des apps lancables, recherche, une case par app. Les apps sensibles (banques, mots de
// passe, paiement, parametres...) sont montrees mais NON selectionnables : la liste noire est dans le natif.
function AppPicker({ selected, onChange, label }: { selected: string[], onChange: (next: string[]) => void, label: string }) {
  const [open, setOpen] = useState(false)
  const [apps, setApps] = useState<AppEntry[] | null>(null)
  const [query, setQuery] = useState("")

  useEffect(() => {
    if (open && apps === null) void listApps().then(setApps)
  }, [open, apps])

  const names = new Map((apps ?? []).map((a) => [a.package, a.label]))
  const q = query.trim().toLowerCase()
  const shown = (apps ?? []).filter((a) => !q || a.label.toLowerCase().includes(q) || a.package.includes(q)).slice(0, 60)
  const toggle = (pkg: string, on: boolean) => onChange(on ? [...selected, pkg] : selected.filter((p) => p !== pkg))

  return (
    <View style={{ gap: 8 }}>
      <Text style={s.dim}>
        {selected.length ? selected.map((p) => names.get(p) ?? p).join(", ") : "Aucune app."}
      </Text>
      <Button kind="ghost" small label={open ? "Fermer la liste" : label} onPress={() => setOpen(!open)} />
      {open && (
        <View style={{ gap: 6 }}>
          <TextInput
            value={query}
            onChangeText={setQuery}
            placeholder="Chercher une app"
            placeholderTextColor={theme.dim}
            autoCapitalize="none"
            style={{ color: theme.text, borderColor: theme.border, borderWidth: 1, borderRadius: 10, paddingHorizontal: 12, minHeight: 48 }}
          />
          {apps === null && <Text style={s.dim}>Chargement des apps…</Text>}
          {shown.map((a) => (
            <Row key={a.package}>
              <View style={{ flex: 1 }}>
                <Text style={s.text}>{a.label}</Text>
                <Text style={[s.dim, { fontSize: 12 }]}>{a.blocked ? "Refusée d'office (sensible)" : a.package}</Text>
              </View>
              <Toggle
                value={selected.includes(a.package)}
                disabled={a.blocked}
                onValueChange={(v) => toggle(a.package, v)}
                accessibilityLabel={`${label} : ${a.label}`}
              />
            </Row>
          ))}
          {apps !== null && shown.length === 0 && <Text style={s.dim}>Aucune app ne correspond.</Text>}
        </View>
      )}
    </View>
  )
}

const KINDS: { key: keyof EventSettings, label: string, hint: string }[] = [
  { key: "missed_call", label: "Appel manqué", hint: "Numéro ou nom de l'appelant (journal d'appels)" },
  { key: "call_ringing", label: "Appel entrant", hint: "Numéro de l'appelant pendant que ça sonne : Aura répond par une fiche client (notification et montre). Exige « Téléphone » et « Journal d'appels »" },
  { key: "battery_low", label: "Batterie basse", hint: "Sous 15 %, une seule fois par descente" },
  { key: "charger", label: "Chargeur branché / débranché", hint: "Avec le niveau de batterie" },
  { key: "zone", label: "Entrée / sortie d'une zone", hint: "Zones Maison / Travail ; à quelques minutes près (dernière position connue)" },
  { key: "notification", label: "Notifications des apps choisies", hint: "Titre et texte tronqués, seulement les apps cochées ci-dessous" },
  { key: "notif_removed", label: "Notification retirée", hint: "App et raison seulement (ouverte, balayée, retirée par l'app), jamais le contenu : Aura repère les apps dont tu balaies tout (bilan du vendredi)" },
]

// Reglages → Declencheurs : ce que le telephone raconte a Aura tout seul. Tout est desactivable ; le serveur
// decide ensuite quoi en faire (phone_triggers.json), et traite le contenu comme une donnee, jamais une consigne.
export function EventsCard() {
  const [ev, setEv] = useState<EventSettings>(readEventSettings)
  const update = (patch: Partial<EventSettings>) => setEv(saveEventSettings({ ...ev, ...patch }))

  return (
    <Card title="Déclencheurs">
      <Text style={s.dim}>
        Le téléphone prévient Aura tout seul. Elle décide quoi en faire selon ses règles (phone_triggers.json sur le
        serveur). Ce que disent tes notifications n'est jamais pris pour un ordre.
      </Text>
      <Row>
        <View style={{ flex: 1 }}>
          <Text style={s.text}>Déclencheurs actifs</Text>
          <Text style={s.dim}>{ev.enabled ? "Activés" : "Tout est coupé"}</Text>
        </View>
        <Toggle value={ev.enabled} onValueChange={(v) => update({ enabled: v })} accessibilityLabel="Déclencheurs actifs" />
      </Row>
      {KINDS.map((k) => (
        <Row key={k.key}>
          <View style={{ flex: 1 }}>
            <Text style={s.text}>{k.label}</Text>
            <Text style={s.dim}>{k.hint}</Text>
          </View>
          <Toggle value={ev[k.key] as boolean} disabled={!ev.enabled}
            onValueChange={(v) => update({ [k.key]: v } as Partial<EventSettings>)} accessibilityLabel={k.label} />
        </Row>
      ))}
      <View style={s.sep} />
      <Text style={s.text}>Apps dont les notifications remontent</Text>
      <Text style={s.dim}>Vide par défaut : rien ne remonte. Exige « Accès aux notifications » (onglet Actions).</Text>
      <AppPicker selected={ev.notif_apps} label="Choisir les apps" onChange={(next) => update({ notif_apps: next })} />
    </Card>
  )
}

// Reglages → Controle d'apps : la liste blanche de ui_act. Vide de tout sauf WhatsApp tant que l'utilisateur n'y touche pas.
export function UiControlCard() {
  const [list, setList] = useState<string[]>(readUiWhitelist)
  // relu au focus et au retour de l'app (l'utilisateur active le service dans les reglages d'Android puis revient)
  const accessible = !!usePermissions().perms.accessibility
  const pause = usePause()
  const paused = isPaused(pause)

  return (
    <Card title="Contrôle d'apps" right={paused ? <Text style={[s.dim, { color: theme.warn, fontWeight: "700" }]}>en pause</Text> : undefined}>
      {paused ? (
        <Text style={[s.text, { color: theme.warn }]}>
          En pause : Aura ne lit plus l'écran et ne touche plus aucune app (Pause d'Aura, en haut de Réglages).
        </Text>
      ) : null}
      <Text style={s.dim}>
        Aura peut toucher, écrire, défiler dans les apps cochées, jamais ailleurs. Toucher et écrire demandent toujours
        ton accord (montre ou téléphone). Banques, paiement, mots de passe, authentificateurs, paramètres et Play Store
        sont refusés d'office, même cochés.
      </Text>
      <Text style={[s.dim, { color: accessible ? theme.ok : theme.warn }]}>
        {accessible ? "Service d'accessibilité activé." : "Service d'accessibilité désactivé : rien ne marche tant qu'il n'est pas activé."}
      </Text>
      <Button kind="ghost" small label="Accessibilité (activer / couper)" onPress={() => { AuraDevice.openSettings("accessibility") }} />
      <AppPicker selected={list} label="Choisir les apps autorisées" onChange={(next) => setList(saveUiWhitelist(next))} />
    </Card>
  )
}
