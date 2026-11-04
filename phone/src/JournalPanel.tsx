import { memo, useCallback, useMemo, useState } from "react"
import { Alert, Text, View } from "react-native"
import { actionLabel, errorLabel } from "./labels"
import { clearJournal, relTime, type JournalEntry, type JournalStatus } from "./native"
import { useTheme } from "./theme"
import { Button, Choice, Dot, useUi, type Tone } from "./ui"

type Filter = "all" | "event" | "action"
const FILTERS: { value: Filter, label: string }[] = [
  { value: "all", label: "Tout" },
  { value: "event", label: "Événements" },
  { value: "action", label: "Actions" },
]
const PAGE = 30

// ce que le journal dit de chaque issue : envoyé / filtré (aucune règle active ou anti-spam) / bloqué / en pause pour un
// événement, fait / échec / refusé pour une action
const STATUS_LABELS: Record<JournalStatus, string> = {
  sent: "envoyé", queued: "en file (hors ligne)", offline: "non envoyé (hors ligne)", filtered: "filtré",
  blocked: "bloqué", paused: "en pause", ok: "fait", failed: "échec", refused: "refusé", timeout: "sans réponse",
}
// événements du téléphone ; les actions ont leurs libellés dans labels.ts (ACTION_LABELS, partagés avec les confirmations)
const KIND_LABELS: Record<string, string> = {
  missed_call: "Appel manqué", call_ringing: "Appel entrant", notification: "Notification", zone: "Zone",
  battery_low: "Batterie basse", charger: "Chargeur", notif_removed: "Notification retirée",
}
const OPS: Record<string, string> = {
  tap: "toucher", type: "saisir", scroll: "défiler", back: "retour", home: "accueil", wait_text: "attendre", lecture: "lecture",
}

export function statusTone(status: JournalStatus): Tone {
  if (status === "ok" || status === "sent") return "ok"
  if (status === "queued" || status === "filtered" || status === "blocked" || status === "paused") return "warn"
  return "bad"
}

function title(e: JournalEntry): string {
  if (e.type === "event") return KIND_LABELS[e.kind] ?? actionLabel(e.kind)
  return actionLabel(e.action ?? e.kind)
}

const Entry = memo(function Entry({ e }: { e: JournalEntry }) {
  const t = useTheme()
  const s = useUi()
  const tone = statusTone(e.status)
  const color = tone === "ok" ? t.ok : tone === "warn" ? t.warn : t.bad
  const target = e.app ? `${e.app}${e.op ? ` · ${OPS[e.op] ?? e.op}` : ""}` : null
  // « filtré : aucune règle active », « filtré : doublon ou trop fréquent », « bloqué : réglage coupé »
  const meta = [
    (STATUS_LABELS[e.status] ?? e.status) + (e.detail && (e.status === "filtered" || e.status === "blocked") ? ` : ${e.detail}` : ""),
    e.status === "filtered" || e.status === "blocked" ? null : e.detail,
    e.type === "action" && e.confirm ? "confirmée" : null,
    e.source === "app" ? "test" : null,
    e.ms !== undefined && e.type === "action" ? `${e.ms} ms` : null,
  ].filter(Boolean).join(" · ")
  // « permission manquante : Envoyer des SMS » plutôt que « permission:sms_send » ; lu aussi par TalkBack (il ne l'était pas)
  const error = e.error && e.error !== e.status ? errorLabel(e.error) : null
  return (
    <View style={{ gap: 2 }} accessible accessibilityLabel={`${title(e)}${target ? `, ${target}` : ""}, ${e.summary}, ${meta}${error ? `, ${error}` : ""}, ${relTime(e.ts)}`}>
      <View style={s.row}>
        <Dot tone={tone} />
        <Text style={[s.text, s.grow, { fontWeight: "600" }]} numberOfLines={2}>{title(e)}{target ? ` · ${target}` : ""}</Text>
        <Text style={s.dim}>{relTime(e.ts)}</Text>
      </View>
      <Text style={s.dim}>{e.summary}</Text>
      <Text style={[s.dim, { color }]}>{meta}{error ? `\n${error}` : ""}</Text>
    </View>
  )
})

/**
 * Journal Aura : ce que le téléphone a raconté à Aura (événements) et ce qu'Aura a fait sur le téléphone (actions, gestes).
 * 200 entrées au plus, gardées au redémarrage, effaçables d'un geste (avec confirmation). Les entrées viennent de l'écran
 * (useJournal), pour que le résumé de la section et la liste ne tiennent qu'un abonnement.
 */
export const JournalPanel = memo(function JournalPanel({ entries }: { entries: JournalEntry[] }) {
  const s = useUi()
  const [filter, setFilter] = useState<Filter>("all")
  const [shown, setShown] = useState(PAGE)
  const list = useMemo(() => entries.filter((e) => filter === "all" || e.type === filter), [entries, filter])
  const pick = useCallback((f: Filter) => { setFilter(f); setShown(PAGE) }, [])
  const confirmClear = () => Alert.alert(
    "Effacer le journal ?",
    `${entries.length} entrée${entries.length > 1 ? "s" : ""} seront supprimées de ce téléphone. Aura n'en garde pas de copie ici.`,
    [{ text: "Annuler", style: "cancel" }, { text: "Effacer", style: "destructive", onPress: () => { clearJournal(); setShown(PAGE) } }],
  )

  return (
    <View style={{ gap: 10 }}>
      <Text style={s.dim}>
        Événements du téléphone envoyés à Aura (ou filtrés, bloqués, en pause) et actions exécutées. Ne sont pas gardés : le
        contenu des notifications (seulement l'app) et le texte des SMS, messages, réponses et saisies. Un appel manqué garde
        le nom ou le numéro, comme le journal d'appels du système. Jamais inclus dans une sauvegarde Android.
      </Text>
      <View style={s.row} accessibilityRole="radiogroup">
        {FILTERS.map((f) => <Choice key={f.value} label={f.label} selected={filter === f.value} onPress={() => pick(f.value)} a11y={`Afficher : ${f.label}`} />)}
      </View>
      {list.length === 0 ? (
        <Text style={s.dim}>Rien pour l'instant. Les événements et les actions s'afficheront ici, avec leur issue.</Text>
      ) : list.slice(0, shown).map((e, i) => (
        <View key={`${e.ts}-${e.kind}-${i}`} style={{ gap: 8 }}>
          {i > 0 && <View style={s.sep} />}
          <Entry e={e} />
        </View>
      ))}
      {list.length > shown && <Button kind="ghost" small label={`Afficher plus (${list.length - shown})`} onPress={() => setShown(shown + PAGE)} />}
      {entries.length > 0 && <Button kind="danger" small label="Effacer le journal" onPress={confirmClear} hint="Demande confirmation" />}
    </View>
  )
})
