import { execSync } from "node:child_process"
import type { ExpoConfig } from "expo/config"

// Configuration fournie au build par l'environnement, jamais ecrite dans un fichier suivi par git :
//   AURA_MOBILE_DEVICE_TOKEN  jeton de l'appareil, emis par votre serveur
//   AURA_BRIDGE_URL           point d'entree WebSocket du serveur (wss://...)
//   AURA_PWA_URL              URL de l'interface web chargee dans l'onglet Aura (https://...)
// Le jeton est embarque dans l'APK : ne pas diffuser un APK construit avec un vrai jeton.
const deviceToken = process.env.AURA_MOBILE_DEVICE_TOKEN ?? ""
const bridgeUrl = process.env.AURA_BRIDGE_URL ?? "wss://example.invalid/ws"
const pwaUrl = process.env.AURA_PWA_URL ?? "https://example.invalid/app/"
// Version REELLE (03/10) : jusqu'ici l'app annoncait « v1.0.0 » build apres build, impossible de savoir quelle APK
// tournait sur le S22. versionName et versionCode s'incrementent a CHAQUE APK publiee (versionCode strictement croissant,
// sinon Android refuse la mise a jour) ; buildId (date + commit, « -dirty » si l'arbre n'etait pas commite) part dans le
// hello et dans device_caps.info, lu par le natif dans le manifeste (plugins/withAuraAndroid.js).
const version = "1.1.0"
const versionCode = 2

function git(args: string): string {
  try {
    return execSync(`git ${args}`, { cwd: __dirname, stdio: ["ignore", "pipe", "ignore"] }).toString().trim()
  } catch {
    return ""
  }
}

function buildId(): string {
  if (process.env.AURA_BUILD_ID) return process.env.AURA_BUILD_ID
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, "0")
  const stamp = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  const sha = git("rev-parse --short HEAD")
  const dirty = sha && git("status --porcelain -- .") ? "-dirty" : ""
  return sha ? `${stamp} ${sha}${dirty}` : stamp
}

const build = buildId()

const config: ExpoConfig = {
  name: "Aura",
  slug: "aura-phone",
  // aura://talk : raccourci « Parler » de l'icone (res/xml/aura_shortcuts.xml du module)
  scheme: "aura",
  version,
  orientation: "portrait",
  icon: "./assets/icon.png",
  backgroundColor: "#101418",
  platforms: ["android"],
  android: {
    package: "dev.aura.mobile",
    versionCode,
    // le journal Aura (prefs) et le jeton ne partent dans aucune sauvegarde Android (cloud ou transfert)
    allowBackup: false,
    adaptiveIcon: {
      foregroundImage: "./assets/adaptive-icon.png",
      backgroundColor: "#101418",
    },
    softwareKeyboardLayoutMode: "resize",
    permissions: [
      "INTERNET",
      "ACCESS_NETWORK_STATE",
      "POST_NOTIFICATIONS",
      "FOREGROUND_SERVICE",
      "FOREGROUND_SERVICE_CONNECTED_DEVICE",
      "FOREGROUND_SERVICE_LOCATION",
      "RECEIVE_BOOT_COMPLETED",
      "SEND_SMS",
      "READ_SMS",
      "CALL_PHONE",
      "READ_CALL_LOG",
      "READ_CONTACTS",
      "READ_CALENDAR",
      "WRITE_CALENDAR",
      "ACCESS_FINE_LOCATION",
      "ACCESS_COARSE_LOCATION",
      "ACCESS_BACKGROUND_LOCATION",
      "com.android.alarm.permission.SET_ALARM",
      "ACCESS_NOTIFICATION_POLICY",
      "RECORD_AUDIO",
      "MODIFY_AUDIO_SETTINGS",
      "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
      "QUERY_ALL_PACKAGES",
      "SYSTEM_ALERT_WINDOW",
      "VIBRATE",
    ],
  },
  plugins: [
    [
      "expo-build-properties",
      {
        android: {
          minSdkVersion: 26,
          compileSdkVersion: 36,
          targetSdkVersion: 36,
          buildToolsVersion: "36.0.0",
        },
      },
    ],
    "./plugins/withAuraAndroid",
  ],
  extra: {
    deviceToken,
    poste: "s22-natif",
    bridgeUrl,
    pwaUrl,
    appVersion: version,
    versionCode,
    buildId: build,
  },
}

export default config
