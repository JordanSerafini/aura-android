import { useFocusEffect } from "@react-navigation/native"
import { useCallback, useEffect, useRef, useState } from "react"
import { AppState, BackHandler, Dimensions, Keyboard, Linking, useWindowDimensions, View } from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"
import { WebView, type WebViewMessageEvent } from "react-native-webview"
import type { ShouldStartLoadRequest } from "react-native-webview/lib/WebViewTypes"
import { DEVICE_TOKEN, POSTE, PWA_ORIGIN, PWA_URL } from "../config"
import { externalUrl, isPwaUrlOf, splitUrl } from "../linkGuards"
import { AuraDevice } from "../native"
import { asThemePref, makeStyles, useThemePref } from "../theme"
import { Message } from "../ui"
import type { DeviceParams, DeviceSection } from "./DeviceScreen"

const PAIRED_KEY = "pwa_paired"

// URL servie par la PWA : origine EGALE, pas un prefixe (garde et raison dans linkGuards.ts, testee par check-link-guards.mjs).
export function isPwaUrl(url: string): boolean {
  return isPwaUrlOf(url, PWA_ORIGIN)
}

// Liens que la WebView peut confier a une autre appli : http(s), mailto et tel SEULEMENT (revue securite du 17/10). Avant,
// tout schema non-PWA partait dans Linking.openURL : intent:, sms:, market:, file:, obsidian:// ou deep link d'une appli tierce
// ouverts par une page de la PWA (ou d'une origine tierce chargee par erreur). Sans espace ni controle : une URL « http » qui en
// contient n'est pas une URL.
export function isExternalLink(url: string): boolean {
  return /^(?:https?:\/\/[^/?#\s]+|mailto:|tel:)[^\s\u0000-\u001f]*$/i.test(url)
}

// Erreur HTTP de la PAGE de la PWA (pas d'une ressource : une image 404 ne doit pas masquer l'app) -> message utile.
// Avant, un 502 du reverse proxy (bridge arrete) laissait une page blanche sans explication.
export function httpErrorMessage(status: number, url: string): string | null {
  if (status < 400) return null
  const u = splitUrl(url)
  const pwa = splitUrl(PWA_URL)
  if (!u || !pwa || u.origin !== PWA_ORIGIN) return null
  const path = u.path
  const base = pwa.path.replace(/\/$/, "")
  if (path !== `${base}/` && path !== base && path !== `${base}/index.html`) return null
  if (status === 401 || status === 403) return `Accès refusé par le serveur (HTTP ${status}) : vérifie que le jeton de cet appareil n'a pas été révoqué.`
  if (status === 404) return "Page introuvable (HTTP 404) : le service bridge du serveur est-il démarré ?"
  if (status >= 500) return `Le serveur ne répond pas (HTTP ${status}) : le service bridge est probablement arrêté.`
  return `Erreur HTTP ${status} en chargeant la PWA.`
}

// Taille de police du systeme (Parametres → Affichage → Taille de la police), bornee : sous 100 % la PWA deviendrait
// illisible, au-dela de 160 % son en-tete deborde. Avant : 100 fixe, le reglage du systeme etait ignore ; puis borne a 130 %,
// mais a 200 % de police systeme le chat restait a 130 % alors que les ecrans natifs suivaient (revue du 17/10) : 160 %, la
// PWA ayant corrige son en-tete pour tenir a cette taille.
export function webTextZoom(fontScale: number, max = 1.6): number {
  const f = Number.isFinite(fontScale) && fontScale > 0 ? fontScale : 1
  return Math.round(Math.min(max, Math.max(1, f)) * 100)
}

// Échec de chargement par RÉSOLUTION DE NOM (Android : net::ERR_NAME_NOT_RESOLVED), pas un refus ni un délai.
export function isNameNotResolved(description: string | undefined): boolean {
  return /ERR_NAME_NOT_RESOLVED|ERR_NAME_RESOLUTION_FAILED/i.test(description ?? "")
}

// Un message par panne : « vérifie le réseau » envoyait chercher la mauvaise panne quand c'est le NOM du serveur qui ne
// se résout pas (DNS du VPN coupé, VPN bien connecté). Les erreurs HTTP ont déjà leur propre explication.
export function errorHint(error: string): string {
  if (isNameNotResolved(error)) return "Le nom du serveur ne se résout pas (DNS du VPN coupé ?)."
  if (/HTTP \d{3}/.test(error)) return "Réessaie dans un instant ; si ça dure, regarde le serveur."
  return "Vérifie la connexion réseau (VPN éventuel) du téléphone et que le serveur est allumé."
}

function initialUrl(): string {
  // 1er lancement : l'URL d'appairage depose le jeton dans le localStorage de la PWA (qui efface le hash)
  if (AuraDevice.getPref(PAIRED_KEY) !== "1" && DEVICE_TOKEN) {
    return `${PWA_URL}#pair=${encodeURIComponent(DEVICE_TOKEN)}&name=${encodeURIComponent(POSTE)}`
  }
  return PWA_URL
}

// Retour Android : qui le prend ? `pwa` = la PWA a quelque chose à défaire (dernier back_state.can) ; `web` = historique
// de la WebView ; `exit` = on ne consomme pas (navigation des écrans natifs, puis sortie de l'app).
export type BackStep = "pwa" | "web" | "exit"
export function backStep(pwaCan: boolean, webCanGoBack: boolean): BackStep {
  return pwaCan ? "pwa" : webCanGoBack ? "web" : "exit"
}
// La PWA ferme sa feuille du dessus, sinon remonte son historique, sinon revient au Chat ; puis renvoie back_state.
export const PWA_BACK = "window.__auraBack && window.__auraBack(); true"

// Injecté dans la PWA. `__auraNativeV = 2` lui dit que cette app connaît `open_device` (avec section) et `open_talk` ; `3`
// (1.4.9, 17/10 soir) : `haptic` (effet système joué par l'app au lieu de navigator.vibrate) ; `4` (1.5.0) : Retour
// Android confié à la PWA (`back_state` → `window.__auraBack()`, voir backStep) ; `5` (1.6.0, 21/10) : gestes du PC portés
// au S22, `copy_text`, `share_text` (feuille de partage), `clip_read` + window.__auraClipHint / __auraClip (§32). La PWA
// renvoie ses pastilles (`badges`) pour la barre des écrans natifs. + la mesure de env(safe-area-inset-bottom), que la WebView
// ne rapporte pas partout : si elle vaut 0, c'est l'app qui réserve la place de la barre système, sinon la PWA le fait déjà.
// + le thème choisi dans Réglages → Apparence de la PWA (clé aura.theme : auto | light | dark), relayé au natif qui le suit.
// Une page qui porte elle-même l'accès à l'écran Appareil le dit en définissant window.__auraDeviceBadge. Sinon (tout autre
// serveur), un bouton rond est posé en bas à droite : sans lui, pause, liste blanche, journal et permissions seraient
// introuvables après l'assistant de premier lancement. Raccourci de l'icône : aura://appareil.
const INJECT = `(() => {
  if (window.__auraNative) return; window.__auraNative = true; window.__auraNativeV = 5
  const post = (m) => window.ReactNativeWebView && window.ReactNativeWebView.postMessage(JSON.stringify(m))
  const probe = document.createElement("div")
  probe.style.cssText = "position:fixed;bottom:0;height:0;padding-bottom:env(safe-area-inset-bottom);visibility:hidden"
  const measure = () => post({ type: "insets", bottom: parseFloat(getComputedStyle(probe).paddingBottom) || 0 })
  // thème de la PWA : à l'ouverture, puis à chaque changement (applyTheme pose ou retire data-theme sur <html>)
  const theme = () => { let v = "auto"; try { v = localStorage.getItem("aura.theme") || "auto" } catch (e) { /* stockage refusé */ } post({ type: "theme", value: v }) }
  // Live Update : l'app native suit les demandes parties de cette PWA (id des chat/voice envoyes). Le
  // prototype est patche, donc la socket deja ouverte par la PWA est couverte aussi.
  const WS = window.WebSocket
  if (WS && !WS.prototype.__auraHooked) {
    WS.prototype.__auraHooked = true
    const send = WS.prototype.send
    WS.prototype.send = function (data) {
      try {
        if (typeof data === "string" && (data.indexOf('"chat"') >= 0 || data.indexOf('"voice"') >= 0)) {
          const m = JSON.parse(data)
          if ((m.type === "chat" || m.type === "voice") && typeof m.id === "string") post({ type: "sent", id: m.id })
        }
      } catch (e) { /* jamais bloquer l'envoi de la PWA */ }
      return send.apply(this, arguments)
    }
  }
  // bouton de repli vers l'écran Appareil, retiré dès que la page déclare son propre accès
  let fab = null
  const fallback = () => {
    if (window.__auraDeviceBadge) { if (fab) { fab.remove(); fab = null } return }
    if (fab) return
    fab = document.createElement("button")
    fab.type = "button"; fab.textContent = "⚙"
    fab.setAttribute("aria-label", "Appareil : pause, permissions, journal, réglages")
    fab.style.cssText = "position:fixed;right:12px;bottom:calc(84px + env(safe-area-inset-bottom));z-index:2147483646;" +
      "width:48px;height:48px;padding:0;border-radius:24px;border:1px solid rgba(127,127,127,.4);" +
      "background:rgba(14,16,23,.85);color:#fff;font-size:20px;line-height:46px;text-align:center"
    fab.addEventListener("click", () => post({ type: "open_device" }))
    document.body.appendChild(fab)
  }
  // pastille de « Plus » et de sa tuile Appareil (page qui porte son accès) ; sinon, pastille du bouton de repli
  window.__auraBadge = (n) => {
    if (window.__auraDeviceBadge) { window.__auraDeviceBadge(n); return }
    if (fab) fab.textContent = n ? "⚙ " + Math.min(99, Number(n) || 0) : "⚙"
  }
  const mount = () => {
    document.body.appendChild(probe)
    measure(); addEventListener("resize", measure)
    theme(); new MutationObserver(theme).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] })
    setTimeout(fallback, 2000); setTimeout(fallback, 8000)
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", mount); else mount()
})(); true`

// pastilles de la PWA (message `badges`, refonte du 15/10), reprises par la barre du bas des écrans natifs
export type PwaBadges = { notifs: number, backlog: number, rappels: number, chat: number, actions: number }

const DEVICE_SECTIONS: DeviceSection[] = ["pause", "permissions", "hud", "automatisations", "journal", "montre", "reglages"]
const count = (v: unknown): number => { const n = Number(v); return Number.isFinite(n) && n > 0 ? Math.min(99, Math.floor(n)) : 0 }

type Props = { onOpenDevice: (params?: DeviceParams) => void, onOpenTalk?: () => void,
  onBadges?: (b: PwaBadges) => void, openHash?: { hash: string, n: number } | null, badge?: number }

export default function AuraScreen({ onOpenDevice, onOpenTalk, onBadges, openHash, badge = 0 }: Props) {
  const st = useStyles()
  const [, setThemePref] = useThemePref()
  const insets = useSafeAreaInsets()
  const { fontScale } = useWindowDimensions()
  const [webBottom, setWebBottom] = useState<number | null>(null)
  const [kb, setKb] = useState(0)
  const ref = useRef<WebView>(null)
  const canGoBack = useRef(false)
  // dernier `back_state.can` de la PWA ; remis à faux à chaque chargement (une page neuve n'a rien à défaire)
  const pwaCanBack = useRef(false)
  const [url] = useState(initialUrl)
  const [error, setError] = useState<string | null>(null)
  const [key, setKey] = useState(0)

  // Retour Android (1.5.0, contrat §26 de PROTOCOL.md) : la PWA dit par `back_state` si ELLE a quelque chose à défaire
  // (feuille ou dialogue ouvert, historique interne, onglet autre que Chat) ; alors on lui passe le Retour
  // (window.__auraBack). Sinon l'historique de la WebView, sinon le Retour sort (écran d'avant, ou l'app se ferme).
  // Une PWA d'avant ne dit jamais `back_state` : on garde le comportement d'avant (historique de la WebView).
  useFocusEffect(useCallback(() => {
    const sub = BackHandler.addEventListener("hardwareBackPress", () => {
      const step = backStep(pwaCanBack.current, canGoBack.current)
      if (step === "pwa") ref.current?.injectJavaScript(PWA_BACK)
      else if (step === "web") ref.current?.goBack()
      return step !== "exit"
    })
    return () => sub.remove()
  }, []))

  // Android 15+ (edge-to-edge impose, targetSdk 36) : adjustResize ne retrecit plus la fenetre, le
  // clavier recouvrait la zone de saisie. On reserve sa hauteur nous-memes ; la PWA voit alors un
  // viewport plus petit (interactive-widget=resizes-content) et remonte son champ au-dessus du clavier.
  useEffect(() => {
    // hauteur prise depuis le HAUT du clavier (screenY) : endCoordinates.height omet la barre de
    // navigation sous le clavier, le champ restait a moitie cache (mesure emulateur Android 16, 27/09)
    const show = Keyboard.addListener("keyboardDidShow", (e) => {
      const fromTop = Dimensions.get("screen").height - e.endCoordinates.screenY
      setKb(Math.max(e.endCoordinates.height, fromTop))
    })
    const hide = Keyboard.addListener("keyboardDidHide", () => {
      setKb(0)
      // geste Retour : le champ garde le focus et la PWA garde sa barre masquee ; on le lui retire (15/10)
      ref.current?.injectJavaScript("document.activeElement && document.activeElement.blur && document.activeElement.blur(); true")
    })
    return () => { show.remove(); hide.remove() }
  }, [])

  useEffect(() => {
    ref.current?.injectJavaScript(`window.__auraBadge && window.__auraBadge(${badge}); true`)
  }, [badge])

  // « Ce que j'ai copié » (1.6.0) : au retour au premier plan, si la PWA est chargée. Le natif ne rend que l'âge d'un
  // texte copié ailleurs (jamais le contenu) ; 500 ms : la fenêtre doit avoir le focus pour lire la description.
  const pwaReady = useRef(false)
  const checkClip = useCallback(() => {
    setTimeout(() => {
      if (!pwaReady.current) return
      const h = AuraDevice.clipHint?.() ?? ""
      if (h) ref.current?.injectJavaScript(`window.__auraClipHint && window.__auraClipHint(${h}); true`)
    }, 500)
  }, [])
  useFocusEffect(useCallback(() => {
    checkClip()
    const sub = AppState.addEventListener("change", (s) => { if (s === "active") checkClip() })
    return () => sub.remove()
  }, [checkClip]))

  // conversation demandee par le partage ou l'assistant (#conv=<id>), onglet Notifs ou bouton d'une
  // notification Android (#notifs, #notif-act=<id>) : la PWA reagit au hashchange ; hors ligne, elle
  // attend le welcome. Si la page charge encore, onLoadEnd la rejoue. Ancre deja filtree par App.hashOf.
  const pendingHash = useRef<string | null>(null)
  useEffect(() => {
    if (!openHash) return
    pendingHash.current = openHash.hash
    ref.current?.injectJavaScript(`location.hash = ${JSON.stringify(openHash.hash)}; true`)
  }, [openHash])

  const onMessage = useCallback((e: WebViewMessageEvent) => {
    // seule la page du serveur configure parle au natif (presse-papiers, ecrans) : une page data:, about: ou d'un autre
    // domaine chargee dans la WebView n'est pas ecoutee
    if (!isPwaUrl(e.nativeEvent.url)) return
    try {
      const m = JSON.parse(e.nativeEvent.data)
      if (m.type === "open_phone") onOpenDevice()
      else if (m.type === "open_settings") onOpenDevice({ section: "reglages", n: Date.now() })
      else if (m.type === "open_device") {
        const section = DEVICE_SECTIONS.find((x) => x === m.section)
        onOpenDevice(section ? { section, n: Date.now() } : undefined)
      }
      else if (m.type === "open_talk") onOpenTalk?.()
      else if (m.type === "badges") onBadges?.({ notifs: count(m.notifs), backlog: count(m.backlog), rappels: count(m.rappels), chat: count(m.chat), actions: count(m.actions) })
      else if (m.type === "theme") { const p = asThemePref(m.value); if (p) setThemePref(p) }
      else if (m.type === "insets") setWebBottom(Number(m.bottom) || 0)
      else if (m.type === "sent" && typeof m.id === "string") AuraDevice.trackRequest(m.id)
      else if (m.type === "haptic" && typeof m.kind === "string" && m.kind.length <= 16) AuraDevice.haptic?.(m.kind)
      else if (m.type === "back_state") pwaCanBack.current = m.can === true
      else if (m.type === "copy_text" && typeof m.text === "string") AuraDevice.copyText?.(m.text)
      else if (m.type === "share_text" && typeof m.text === "string") AuraDevice.shareText?.(m.text, typeof m.subject === "string" ? m.subject : "")
      else if (m.type === "clip_read") {
        const t = AuraDevice.clipText?.() ?? ""
        ref.current?.injectJavaScript(`window.__auraClip && window.__auraClip(${JSON.stringify(t)}); true`)
      }
    } catch { /* message d'une autre origine : ignore */ }
  }, [onOpenDevice, onOpenTalk, onBadges, setThemePref])

  // la PWA gere deja la barre systeme si elle voit un inset > 0 ; tant qu'on ne sait pas, on reserve
  const navPad = webBottom === null || webBottom < 1 ? insets.bottom : 0
  const bottomPad = kb > 0 ? kb : navPad

  const onShouldStart = useCallback((req: ShouldStartLoadRequest) => {
    const u = req.url
    if (isPwaUrl(u) || u.startsWith("about:") || u.startsWith("data:") || u.startsWith("blob:")) return true
    // liens http(s) hors domaine, mailto:, tel: : appli du systeme ; tout autre schema (intent:, sms:, market:, file:,
    // obsidian:...) est ignore, sans rien ouvrir
    if (isExternalLink(u)) Linking.openURL(externalUrl(u)).catch(() => {})  // sans le jeton d'appairage herite (F2)
    return false
  }, [])

  return (
    <View style={[st.root, { paddingTop: insets.top, paddingBottom: bottomPad, paddingLeft: insets.left, paddingRight: insets.right }]}>
      {error ? (
        <View style={st.error}>
          <Message kind="error" title="Aura injoignable" text={`${error}\n${errorHint(error)}`}
            onRetry={() => {
              setError(null)
              setKey((k) => k + 1)
            }} />
        </View>
      ) : (
        <WebView
          key={key}
          ref={ref}
          source={{ uri: url }}
          style={st.web}
          originWhitelist={["*"]}
          javaScriptEnabled
          domStorageEnabled
          cacheEnabled
          allowsInlineMediaPlayback
          mediaPlaybackRequiresUserAction={false}
          mediaCapturePermissionGrantType="grant"
          setSupportMultipleWindows={false}
          startInLoadingState
          renderLoading={() => <View style={st.loading}><Message kind="loading" text="Chargement d'Aura…" /></View>}
          onShouldStartLoadWithRequest={onShouldStart}
          injectedJavaScript={INJECT}
          onMessage={onMessage}
          onNavigationStateChange={(nav) => { canGoBack.current = nav.canGoBack }}
          onLoadStart={() => { pwaCanBack.current = false; pwaReady.current = false }}
          onLoadEnd={(e) => {
            const here = e.nativeEvent.url
            if (isPwaUrl(here) && !e.nativeEvent.loading) {
              AuraDevice.setPref(PAIRED_KEY, "1")
              if (pendingHash.current) ref.current?.injectJavaScript(`location.hash = ${JSON.stringify(pendingHash.current)}; true`)
              pendingHash.current = null
              ref.current?.injectJavaScript(`window.__auraBadge && window.__auraBadge(${badge}); true`)
              pwaReady.current = true
              checkClip()
            }
          }}
          onError={(e) => setError(e.nativeEvent.description || "erreur réseau")}
          onHttpError={(e) => {
            const msg = httpErrorMessage(e.nativeEvent.statusCode, e.nativeEvent.url)
            if (msg) setError(msg)
          }}
          onRenderProcessGone={() => { pwaCanBack.current = false; setKey((k) => k + 1) }}
          overScrollMode="never"
          textZoom={webTextZoom(fontScale)}
          webviewDebuggingEnabled={__DEV__}
        />
      )}
    </View>
  )
}

const useStyles = makeStyles((t) => ({
  root: { flex: 1, backgroundColor: t.bg },
  web: { flex: 1, backgroundColor: t.bg },
  loading: { position: "absolute", top: 0, bottom: 0, left: 0, right: 0, backgroundColor: t.bg, justifyContent: "center" },
  error: { flex: 1, justifyContent: "center" },
}))
