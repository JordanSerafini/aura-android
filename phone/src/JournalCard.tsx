import { useState } from "react"
import { Alert, Text, View } from "react-native"
import { theme } from "./config"
import { clearJournal, relTime, useJournal, type JournalEntry, type JournalStatus } from "./native"
import { Button, Card, Choice, Dot, Row, s } from "./ui"

type Filter = "all" | "event" | "action"
const FILTERS: { value: Filter, label: string }[] = [
  { value: "all", label: "Tout" },
  { value: "event", label: "Événements" },
  { value: "action", label: "Actions" },
]
const PAGE = 30

// ce que le journal dit de chaque issue : envoye / filtre (aucune regle active ou anti-spam) / bloque / en pause pour un evenement,
// fait / echec / refuse pour une action
const STATUS_LABELS: Record<JournalStatus, string> = {
  sent: "envoyé", queued: "en file (hors ligne)", offline: "non envoyé (hors ligne)", filtered: "filtré",
  blocked: "bloqué", paused: "en pause", ok: "fait", failed: "échec", refused: "refusé", timeout: "sans réponse",
}
const KIND_LABELS: Record<string, string> = {
  missed_call: "Appel manqué", call_ringing: "Appel entrant", notification: "Notification", zone: "Zone",
  battery_low: "Batterie basse", charger: "Chargeur", ui_act: "Contrôle d'app", screen_read: "Lecture d'écran",
  notif_removed: "Notification retirée", app_usage: "Temps d'écran",
}
const OPS: Record<string, string> = {
  tap: "toucher", type: "saisir", scroll: "défiler", back: "retour", home: "accueil", wait_text: "attendre", lecture: "lecture",
}

export function statusTone(status: JournalStatus): "ok" | "warn" | "bad" {
  if (status === "ok" || status === "sent") return "ok"
  if (status === "queued" || status === "filtered" || status === "blocked" || status === "paused") return "warn"
  return "bad"
}

function title(e: JournalEntry): string {
  const k = e.type === "event" ? e.kind : e.action ?? e.kind
  return KIND_LABELS[k] ?? k
}

function Entry({ e }: { e: JournalEntry }) {
  const tone = statusTone(e.status)
  const color = tone === "ok" ? theme.ok : tone === "warn" ? theme.warn : theme.bad
  const target = e.app ? `${e.app}${e.op ? ` · ${OPS[e.op] ?? e.op}` : ""}` : null
  // « filtré : aucune règle active », « filtré : doublon ou trop fréquent », « bloqué : réglage coupé »
  const meta = [
    (STATUS_LABELS[e.status] ?? e.status) + (e.detail && (e.status === "filtered" || e.status === "blocked") ? ` : ${e.detail}` : ""),
    e.status === "filtered" || e.status === "blocked" ? null : e.detail,
    e.type === "action" && e.confirm ? "confirmée" : null,
    e.source === "app" ? "test" : null,
    e.ms !== undefined && e.type === "action" ? `${e.ms} ms` : null,
  ].filter(Boolean).join(" · ")
  return (
    <View style={{ gap: 2 }} accessible accessibilityLabel={`${title(e)}, ${e.summary}, ${STATUS_LABELS[e.status] ?? e.status}, ${relTime(e.ts)}`}>
      <Row>
        <Dot ok={tone === "ok"} warn={tone === "warn"} />
        <Text style={[s.text, { flex: 1, fontWeight: "600" }]} numberOfLines={1}>{title(e)}{target ? ` · ${target}` : ""}</Text>
        <Text style={s.dim}>{relTime(e.ts)}</Text>
      </Row>
      <Text style={s.dim}>{e.summary}</Text>
      <Text style={[s.dim, { color }]}>{meta}{e.error && e.error !== e.status ? `\n${e.error}` : ""}</Text>
    </View>
  )
}

/**
 * Journal Aura : ce que le téléphone a raconté à Aura (événements) et ce qu'Aura a fait sur le téléphone (actions, gestes).
 * Remplace « Dernières actions » : les actions y sont toujours, avec les mêmes détails, à côté des événements qui n'étaient
 * visibles que dans logcat. 200 entrées au plus, gardées au redémarrage, effaçables d'un geste (avec confirmation).
 */
export default function JournalCard({ limit = PAGE, compact = false, onOpenAll }: { limit?: number, compact?: boolean, onOpenAll?: () => void }) {
  const entries = useJournal()
  const [filter, setFilter] = useState<Filter>("all")
  const [shown, setShown] = useState(limit)
  const list = entries.filter((e) => filter === "all" || e.type === filter)
  const confirmClear = () => Alert.alert(
    "Effacer le journal ?",
    `${entries.length} entrée${entries.length > 1 ? "s" : ""} seront supprimées de ce téléphone. Aura n'en garde pas de copie ici.`,
    [{ text: "Annuler", style: "cancel" }, { text: "Effacer", style: "destructive", onPress: () => { clearJournal(); setShown(limit) } }],
  )

  return (
    <Card title="Journal Aura" right={<Text style={s.dim}>{entries.length} entrée{entries.length > 1 ? "s" : ""}</Text>}>
      <Text style={s.dim}>
        Événements du téléphone envoyés à Aura (ou filtrés, bloqués, en pause) et actions exécutées. Ne sont pas gardés : le
        contenu des notifications (seulement l'app) et le texte des SMS, messages, réponses et saisies (seulement le type de
        destinataire et le nombre de caractères). Un appel manqué garde le nom ou le numéro, comme le journal d'appels du
        système. 200 entrées au plus, jamais incluses dans une sauvegarde Android.
      </Text>
      {!compact && (
        <Row>
          {FILTERS.map((f) => <Choice key={f.value} label={f.label} selected={filter === f.value} onPress={() => { setFilter(f.value); setShown(limit) }} />)}
        </Row>
      )}
      {list.length === 0 ? (
        <Text style={s.dim}>Rien pour l'instant. Les événements et les actions s'afficheront ici, avec leur issue.</Text>
      ) : list.slice(0, shown).map((e, i) => (
        <View key={`${e.ts}-${i}`} style={{ gap: 8 }}>
          {i > 0 && <View style={s.sep} />}
          <Entry e={e} />
        </View>
      ))}
      {list.length > shown && <Button kind="ghost" small label={`Afficher plus (${list.length - shown})`} onPress={() => setShown(shown + PAGE)} />}
      {onOpenAll && <Button kind="ghost" small label="Ouvrir le Journal Aura" onPress={onOpenAll} />}
      {entries.length > 0 && <Button kind="danger" small label="Effacer le journal" onPress={confirmClear} />}
    </Card>
  )
}
