import { useFocusEffect } from "@react-navigation/native"
import { useCallback, useEffect, useRef, useState } from "react"
import { ActivityIndicator, BackHandler, Dimensions, Keyboard, Linking, StyleSheet, Text, useWindowDimensions, View } from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"
import { WebView, type WebViewMessageEvent } from "react-native-webview"
import type { ShouldStartLoadRequest } from "react-native-webview/lib/WebViewTypes"
import { DEVICE_TOKEN, POSTE, PWA_ORIGIN, PWA_URL, theme } from "../config"
import { AuraDevice } from "../native"
import { Button } from "../ui"

const PAIRED_KEY = "pwa_paired"

// Origine et chemin d'une URL http(s), SANS `URL` : sous Hermes le polyfill de `URL` n'implemente pas tous les accesseurs
// (`pathname`, `origin` levent « not implemented » selon la version de React Native).
function splitUrl(url: string): { origin: string, path: string } | null {
  const m = /^(https?:\/\/[^/?#]+)([^?#]*)/i.exec(url)
  return m ? { origin: m[1], path: m[2] || "/" } : null
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
// illisible, au-dela de 130 % son en-tete deborde. Avant : 100 fixe, le reglage du systeme etait ignore.
export function webTextZoom(fontScale: number): number {
  const f = Number.isFinite(fontScale) && fontScale > 0 ? fontScale : 1
  return Math.round(Math.min(1.3, Math.max(1, f)) * 100)
}

function initialUrl(): string {
  // 1er lancement : l'URL d'appairage depose le jeton dans le localStorage de la PWA (qui efface le hash)
  if (AuraDevice.getPref(PAIRED_KEY) !== "1" && DEVICE_TOKEN) {
    return `${PWA_URL}#pair=${encodeURIComponent(DEVICE_TOKEN)}&name=${encodeURIComponent(POSTE)}`
  }
  return PWA_URL
}

// Injecte dans la PWA : trois boutons dans l'en-tete (la barre d'onglets native est masquee sur cet onglet) :
// 🎧 conversation vocale, ⚙ « Réglages du téléphone » (acces direct a l'onglet Réglages natif) et 📱 « Téléphone »
// (accueil des ecrans natifs : etat, pause, journal, permissions, montre). La PWA a ses propres reglages (son menu) :
// les deux ne portent plus le meme nom. + la mesure de env(safe-area-inset-bottom), que la WebView ne rapporte pas
// partout : si elle vaut 0, c'est l'app qui reserve la place de la barre systeme, sinon la PWA le fait deja et on
// n'ajoute rien (sinon bande vide). Chaque bouton fait 48 px de haut et de large (cible tactile) pour un dessin de
// 34 px, sans agrandir l'en-tete (marges negatives). Defensif : si l'en-tete change de forme, les boutons flottent
// en haut a droite.
const INJECT = `(() => {
  if (window.__auraNative) return; window.__auraNative = true
  const post = (m) => window.ReactNativeWebView && window.ReactNativeWebView.postMessage(JSON.stringify(m))
  const probe = document.createElement("div")
  probe.style.cssText = "position:fixed;bottom:0;height:0;padding-bottom:env(safe-area-inset-bottom);visibility:hidden"
  const measure = () => post({ type: "insets", bottom: parseFloat(getComputedStyle(probe).paddingBottom) || 0 })
  // la CSP de la PWA (style-src 'self') bloque toute balise <style> injectee : styles poses par le CSSOM
  const put = (el, props) => { for (const k in props) el.style.setProperty(k, props[k], "important") }
  const mk = (label, glyph, type) => {
    const b = document.createElement("button")
    b.type = "button"
    put(b, { margin: "-7px 0 -7px 3px", "min-height": "0", height: "48px", width: "48px", padding: "0", flex: "none", border: "0",
      background: "transparent", position: "relative", "box-shadow": "none", color: "inherit", display: "flex", "align-items": "center",
      "justify-content": "center" })
    const face = document.createElement("span")
    face.setAttribute("aria-hidden", "true")
    put(face, { display: "block", height: "34px", width: "34px", border: "1px solid rgba(127,127,127,.35)", "border-radius": "999px",
      "font-size": "17px", "line-height": "32px", "text-align": "center" })
    face.textContent = glyph
    b.appendChild(face); b.setAttribute("aria-label", label)
    b.addEventListener("click", () => post({ type }))
    return b
  }
  const btn = mk("Téléphone : état, journal, permissions, montre", "📱", "open_phone")
  const gear = mk("Réglages du téléphone", "⚙️", "open_settings")
  // bouton 🎧 : mode conversation vocale natif (TalkScreen), mains libres
  const talk = mk("Conversation vocale", "🎧", "open_talk")
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
  window.__auraBadge = (n) => {
    let b = btn.querySelector("b")
    if (!n) { if (b) b.remove(); return }
    if (!b) {
      b = document.createElement("b")
      put(b, { position: "absolute", top: "1px", right: "1px", "min-width": "18px", height: "18px", "border-radius": "9px",
        background: "#e5484d", color: "#fff", "font-size": "11px", "line-height": "18px", padding: "0 4px" })
      btn.appendChild(b)
    }
    b.textContent = String(n)
  }
  const mount = () => {
    document.body.appendChild(probe)
    const header = document.querySelector("header.top"); const status = document.getElementById("status")
    if (header && status) { put(status, { "margin-left": "auto" }); header.appendChild(talk); header.appendChild(gear); header.appendChild(btn) }
    else {
      put(btn, { position: "fixed", top: "calc(env(safe-area-inset-top) + 4px)", right: "4px", "z-index": "99", margin: "0" }); document.body.appendChild(btn)
      put(gear, { position: "fixed", top: "calc(env(safe-area-inset-top) + 4px)", right: "52px", "z-index": "99", margin: "0" }); document.body.appendChild(gear)
      put(talk, { position: "fixed", top: "calc(env(safe-area-inset-top) + 4px)", right: "100px", "z-index": "99", margin: "0" }); document.body.appendChild(talk)
    }
    measure(); addEventListener("resize", measure)
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", mount); else mount()
})(); true`

type Props = { onOpenPhone: () => void, onOpenSettings: () => void, onOpenTalk?: () => void, openHash?: { hash: string, n: number } | null, badge?: number }

export default function AuraScreen({ onOpenPhone, onOpenSettings, onOpenTalk, openHash, badge = 0 }: Props) {
  const insets = useSafeAreaInsets()
  const { fontScale } = useWindowDimensions()
  const [webBottom, setWebBottom] = useState<number | null>(null)
  const [kb, setKb] = useState(0)
  const ref = useRef<WebView>(null)
  const canGoBack = useRef(false)
  const [url] = useState(initialUrl)
  const [error, setError] = useState<string | null>(null)
  const [key, setKey] = useState(0)

  // bouton retour Android = retour dans la WebView, tant qu'elle a un historique
  useFocusEffect(useCallback(() => {
    const sub = BackHandler.addEventListener("hardwareBackPress", () => {
      if (canGoBack.current) {
        ref.current?.goBack()
        return true
      }
      return false
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
    const hide = Keyboard.addListener("keyboardDidHide", () => setKb(0))
    return () => { show.remove(); hide.remove() }
  }, [])

  useEffect(() => {
    ref.current?.injectJavaScript(`window.__auraBadge && window.__auraBadge(${badge}); true`)
  }, [badge])

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
    try {
      const m = JSON.parse(e.nativeEvent.data)
      if (m.type === "open_phone") onOpenPhone()
      else if (m.type === "open_settings") onOpenSettings()
      else if (m.type === "open_talk") onOpenTalk?.()
      else if (m.type === "insets") setWebBottom(Number(m.bottom) || 0)
      else if (m.type === "sent" && typeof m.id === "string") AuraDevice.trackRequest(m.id)
    } catch { /* message d'une autre origine : ignore */ }
  }, [onOpenPhone, onOpenSettings, onOpenTalk])

  // la PWA gere deja la barre systeme si elle voit un inset > 0 ; tant qu'on ne sait pas, on reserve
  const navPad = webBottom === null || webBottom < 1 ? insets.bottom : 0
  const bottomPad = kb > 0 ? kb : navPad

  const onShouldStart = useCallback((req: ShouldStartLoadRequest) => {
    const u = req.url
    if (u.startsWith(PWA_ORIGIN) || u.startsWith("about:") || u.startsWith("data:") || u.startsWith("blob:")) return true
    // obsidian://, liens http hors domaine, mailto:, tel: : appli du systeme
    Linking.openURL(u).catch(() => {})
    return false
  }, [])

  return (
    <View style={[styles.root, { paddingTop: insets.top, paddingBottom: bottomPad }]}>
      {error ? (
        <View style={styles.error}>
          <Text style={styles.errorTitle}>Aura injoignable</Text>
          <Text style={styles.errorText}>{error}</Text>
          <Text style={styles.errorText}>Vérifie la connexion réseau (VPN éventuel) du téléphone.</Text>
          <Button label="Réessayer" onPress={() => { setError(null); setKey((k) => k + 1) }} />
        </View>
      ) : (
        <WebView
          key={key}
          ref={ref}
          source={{ uri: key === 0 ? url : PWA_URL }}
          style={styles.web}
          originWhitelist={["*"]}
          javaScriptEnabled
          domStorageEnabled
          cacheEnabled
          allowsInlineMediaPlayback
          mediaPlaybackRequiresUserAction={false}
          mediaCapturePermissionGrantType="grant"
          setSupportMultipleWindows={false}
          startInLoadingState
          renderLoading={() => <ActivityIndicator style={styles.loading} color={theme.accent} size="large" />}
          onShouldStartLoadWithRequest={onShouldStart}
          injectedJavaScript={INJECT}
          onMessage={onMessage}
          onNavigationStateChange={(nav) => { canGoBack.current = nav.canGoBack }}
          onLoadEnd={(e) => {
            if (e.nativeEvent.url.startsWith(PWA_ORIGIN) && !e.nativeEvent.loading) {
              AuraDevice.setPref(PAIRED_KEY, "1")
              if (pendingHash.current) ref.current?.injectJavaScript(`location.hash = ${JSON.stringify(pendingHash.current)}; true`)
              pendingHash.current = null
              ref.current?.injectJavaScript(`window.__auraBadge && window.__auraBadge(${badge}); true`)
            }
          }}
          onError={(e) => setError(e.nativeEvent.description || "erreur réseau")}
          onHttpError={(e) => {
            const msg = httpErrorMessage(e.nativeEvent.statusCode, e.nativeEvent.url)
            if (msg) setError(msg)
          }}
          onRenderProcessGone={() => setKey((k) => k + 1)}
          overScrollMode="never"
          textZoom={webTextZoom(fontScale)}
          webviewDebuggingEnabled={__DEV__}
        />
      )}
    </View>
  )
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: theme.bg },
  web: { flex: 1, backgroundColor: theme.bg },
  loading: { position: "absolute", top: 0, bottom: 0, left: 0, right: 0 },
  error: { flex: 1, alignItems: "center", justifyContent: "center", padding: 24, gap: 12 },
  errorTitle: { color: theme.text, fontSize: 20, fontWeight: "700" },
  errorText: { color: theme.dim, fontSize: 14, textAlign: "center" },
})
