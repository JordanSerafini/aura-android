import { memo, useCallback, useEffect, useMemo, useState } from "react"
import { Alert, Text, TextInput, View } from "react-native"
import { OUTGOING } from "./config"
import {
  AuraDevice, listApps, readEventSettings, readUiWhitelist, saveEventSettings, saveUiWhitelist,
  type AppEntry, type AuraState, type EventSettings,
} from "./native"
import { useTheme } from "./theme"
import { Button, Message, ToggleRow, useUi } from "./ui"

const AppRow = memo(function AppRow({ app, on, onToggle }: { app: AppEntry, on: boolean, onToggle: (pkg: string, on: boolean) => void }) {
  return (
    <ToggleRow label={app.label} hint={app.blocked ? "Refusée d'office (sensible)" : app.package} value={on} disabled={app.blocked}
      onChange={(v) => onToggle(app.package, v)} hintTone={app.blocked ? "warn" : undefined} />
  )
})

// Sélecteur d'apps : liste des apps lançables, recherche, un interrupteur par app. Les apps sensibles (banques, mots de
// passe, paiement, paramètres...) sont montrées mais NON sélectionnables : la liste noire est dans le natif.
function AppPicker({ selected, onChange, label }: { selected: string[], onChange: (next: string[]) => void, label: string }) {
  const t = useTheme()
  const s = useUi()
  const [open, setOpen] = useState(false)
  const [apps, setApps] = useState<AppEntry[] | null>(null)
  const [error, setError] = useState(false)
  const [query, setQuery] = useState("")

  const load = useCallback(() => {
    setError(false)
    listApps().then((list) => { setApps(list); if (!list.length) setError(true) }, () => setError(true))
  }, [])
  useEffect(() => { if (open && apps === null) load() }, [open, apps, load])

  const names = useMemo(() => new Map((apps ?? []).map((a) => [a.package, a.label])), [apps])
  const q = query.trim().toLowerCase()
  const shown = useMemo(() => (apps ?? []).filter((a) => !q || a.label.toLowerCase().includes(q) || a.package.includes(q)).slice(0, 60), [apps, q])
  const toggle = useCallback((pkg: string, on: boolean) => onChange(on ? [...selected, pkg] : selected.filter((p) => p !== pkg)), [onChange, selected])

  return (
    <View style={{ gap: 8 }}>
      <Text style={s.dim}>{selected.length ? selected.map((p) => names.get(p) ?? p).join(", ") : "Aucune app."}</Text>
      <Button kind="ghost" small label={open ? "Fermer la liste" : label} onPress={() => setOpen(!open)} a11y={open ? `Fermer la liste : ${label}` : label} />
      {open && (
        <View style={{ gap: 6 }}>
          <TextInput value={query} onChangeText={setQuery} placeholder="Chercher une app" placeholderTextColor={t.dim} autoCapitalize="none"
            accessibilityLabel="Chercher une app" style={s.input} />
          {apps === null && !error ? <Message kind="loading" text="Chargement des apps…" /> : null}
          {error ? <Message kind="error" text="Liste des apps illisible." onRetry={() => { setApps(null); load() }} /> : null}
          {shown.map((a) => <AppRow key={a.package} app={a} on={selected.includes(a.package)} onToggle={toggle} />)}
          {apps !== null && apps.length > 0 && shown.length === 0 && <Text style={s.dim}>Aucune app ne correspond.</Text>}
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
  { key: "notification", label: "Notifications des apps choisies", hint: "Titre et texte tronqués, seulement les apps choisies ci-dessous" },
  { key: "notif_removed", label: "Notification retirée", hint: "App et raison seulement, jamais le contenu : Aura repère les apps dont tu balaies tout (bilan du vendredi)" },
]

// Déclencheurs : ce que le téléphone raconte à Aura tout seul. Tout est désactivable ; le serveur décide ensuite quoi en faire
// (phone_triggers.json), et traite le contenu comme une donnée, jamais une consigne.
export const EventsPanel = memo(function EventsPanel() {
  const s = useUi()
  const [ev, setEv] = useState<EventSettings>(readEventSettings)
  const update = useCallback((patch: Partial<EventSettings>) => setEv((prev) => saveEventSettings({ ...prev, ...patch })), [])

  return (
    <View style={{ gap: 6 }}>
      <Text style={s.strong} accessibilityRole="header">Déclencheurs</Text>
      <Text style={s.dim}>
        Le téléphone prévient Aura tout seul. Elle décide quoi en faire selon ses règles (phone_triggers.json sur le serveur).
        Ce que disent tes notifications n'est jamais pris pour un ordre.
      </Text>
      <ToggleRow label="Déclencheurs actifs" hint={ev.enabled ? "Activés" : "Tout est coupé"} value={ev.enabled} onChange={(v) => update({ enabled: v })} />
      {KINDS.map((k) => (
        <ToggleRow key={k.key} label={k.label} hint={k.hint} value={ev[k.key] as boolean} disabled={!ev.enabled}
          onChange={(v) => update({ [k.key]: v } as Partial<EventSettings>)} />
      ))}
      <Text style={s.text}>Apps dont les notifications remontent</Text>
      <Text style={s.dim}>Vide par défaut : rien ne remonte. Exige « Accès aux notifications » (section Permissions).</Text>
      <AppPicker selected={ev.notif_apps} label="Choisir les apps" onChange={(next) => update({ notif_apps: next })} />
    </View>
  )
})

// Contrôle d'apps : la liste blanche de ui_act. Vide de tout sauf WhatsApp tant que l'utilisateur n'y touche pas.
export const UiControlPanel = memo(function UiControlPanel({ accessible, paused }: { accessible: boolean, paused: boolean }) {
  const t = useTheme()
  const s = useUi()
  const [list, setList] = useState<string[]>(readUiWhitelist)
  const save = useCallback((next: string[]) => setList(saveUiWhitelist(next)), [])

  return (
    <View style={{ gap: 6 }}>
      <Text style={s.strong} accessibilityRole="header">Contrôle d'apps</Text>
      {paused ? (
        <Text style={[s.text, { color: t.warn }]}>En pause : Aura ne lit plus l'écran et ne touche plus aucune app (section Pause d'Aura).</Text>
      ) : null}
      <Text style={s.dim}>
        Aura peut toucher, écrire, défiler dans les apps choisies, jamais ailleurs. Toucher et écrire demandent toujours ton accord
        (montre ou téléphone). Banques, paiement, mots de passe, authentificateurs, paramètres et Play Store sont refusés d'office.
      </Text>
      <Text style={[s.dim, { color: accessible ? t.ok : t.warn }]}>
        {accessible ? "Service d'accessibilité activé." : "Service d'accessibilité désactivé : rien ne marche tant qu'il n'est pas activé."}
      </Text>
      <Button kind="ghost" small label="Accessibilité (activer / couper)" onPress={() => { AuraDevice.openSettings("accessibility") }}
        hint="Ouvre les réglages d'accessibilité d'Android" />
      <AppPicker selected={list} label="Choisir les apps autorisées" onChange={save} />
    </View>
  )
})

// « Demander mon accord avant… » : la valeur effective vient du bridge (settings.phone_confirm), modifiable seulement en ligne
export const ConfirmPolicyPanel = memo(function ConfirmPolicyPanel({ bridge, phoneConfirm }: { bridge: AuraState["bridge"], phoneConfirm: AuraState["phoneConfirm"] }) {
  const s = useUi()
  const t = useTheme()
  const known = phoneConfirm !== null
  const map: Record<string, boolean> = phoneConfirm ?? {}
  const online = bridge === "online"
  const setConfirm = (action: string, value: boolean) => {
    const next: Record<string, boolean> = {}
    for (const o of OUTGOING) next[o.action] = map[o.action] ?? true
    next[action] = value
    // l'interrupteur suit l'état renvoyé par le bridge : si l'envoi échoue il reste où il était, autant dire pourquoi
    if (!AuraDevice.sendBridgeSettings(JSON.stringify({ phone_confirm: next }))) {
      Alert.alert("Réglage non envoyé", "Le serveur est injoignable : la confirmation reste telle qu'elle était. Réessaie une fois connecté.")
    }
  }
  // couper une confirmation = Aura agira sans demander : un appui par erreur ne doit pas suffire (remettre la confirmation reste immédiat)
  const changeConfirm = (action: string, label: string, value: boolean) => {
    if (value) {
      setConfirm(action, true)
      return
    }
    Alert.alert(
      "Agir sans ton accord ?",
      `Aura pourra « ${label} » sans te demander. Ce qui part ne se rappelle pas. Tu pourras remettre la confirmation ici.`,
      [
        { text: "Garder la confirmation", style: "cancel" },
        { text: "Désactiver", style: "destructive", onPress: () => setConfirm(action, false) },
      ],
    )
  }
  return (
    <View style={{ gap: 6 }}>
      <Text style={s.strong} accessibilityRole="header">Demander mon accord avant…</Text>
      <Text style={s.dim}>Activé : Aura te montre l'action et attend ton « Accepter ». Désactivé : Aura agit sans demander.</Text>
      {!known || !online ? (
        <Text style={[s.dim, { color: t.warn }]}>{online ? "Le serveur n'a pas encore envoyé ses réglages : valeurs par défaut affichées." : "Hors connexion : réglages en lecture seule."}</Text>
      ) : null}
      {OUTGOING.map((o) => {
        const on = map[o.action] ?? true
        return (
          <ToggleRow key={o.action} label={o.label} hint={on ? "Avec confirmation" : "Sans confirmation"} hintTone={on ? undefined : "warn"}
            value={on} disabled={!online} onChange={(v) => changeConfirm(o.action, o.label, v)} />
        )
      })}
    </View>
  )
})
