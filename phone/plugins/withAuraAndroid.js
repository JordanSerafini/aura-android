/**
 * withAuraAndroid : ce que `expo prebuild --clean` doit regenerer a chaque fois.
 *
 * - Signature release avec le keystore fourni par l'environnement AU BUILD (AURA_KEYSTORE_FILE,
 *   AURA_KEYSTORE_ALIAS, AURA_MOBILE_KEYSTORE_PASS) : rien n'est ecrit dans un fichier suivi.
 *   Sans keystore, assembleRelease echoue : utiliser assembleDebug. Meme cle que la montre, obligatoire pour que
 *   le Data Layer relie les deux apps.
 * - ABI arm64-v8a seule (Galaxy S22) : build 4x plus court, APK plus leger.
 *
 * - Identifiant de build (extra.buildId d'app.config.ts : date + commit) en meta-data de l'application : le natif
 *   l'annonce dans le hello et device_caps.info sans dependre du JS (le service demarre aussi au boot, JS eteint).
 *
 * Le manifeste (services, recepteurs, permissions) et la capability Data Layer (res/values/wear.xml)
 * vivent dans le module local modules/aura-device : fusionnes par Gradle, ils survivent au prebuild.
 */
const { withAndroidManifest, withAppBuildGradle, withGradleProperties } = require("expo/config-plugins")

const SIGNING = `
        release {
            storeFile file(System.getenv("AURA_KEYSTORE_FILE") ?: "missing.jks")
            storePassword System.getenv("AURA_MOBILE_KEYSTORE_PASS") ?: ""
            keyAlias System.getenv("AURA_KEYSTORE_ALIAS") ?: "aura"
            keyPassword System.getenv("AURA_MOBILE_KEYSTORE_PASS") ?: ""
        }`

function withReleaseSigning(config) {
  return withAppBuildGradle(config, (cfg) => {
    let src = cfg.modResults.contents
    if (!src.includes("AURA_MOBILE_KEYSTORE_PASS")) {
      src = src.replace(/signingConfigs\s*\{/, (m) => `${m}${SIGNING}`)
      // dans buildTypes.release uniquement : le debug garde la cle de debug
      src = src.replace(/(release\s*\{[^}]*?)signingConfig signingConfigs\.debug/, "$1signingConfig signingConfigs.release")
    }
    cfg.modResults.contents = src
    return cfg
  })
}

function withArm64Only(config) {
  return withGradleProperties(config, (cfg) => {
    const set = (key, value) => {
      const item = cfg.modResults.find((p) => p.type === "property" && p.key === key)
      if (item) item.value = value
      else cfg.modResults.push({ type: "property", key, value })
    }
    set("reactNativeArchitectures", "arm64-v8a")
    set("org.gradle.jvmargs", "-Xmx4096m -XX:MaxMetaspaceSize=1024m")
    return cfg
  })
}

// Raccourci « Parler » (appui long sur l'icone) : la ressource vit dans le module, la meta-data doit
// etre sur l'activite du lanceur, generee par le prebuild.
function withShortcuts(config) {
  return withAndroidManifest(config, (cfg) => {
    const app = cfg.modResults.manifest.application?.[0]
    const main = app?.activity?.find((a) => a.$["android:name"] === ".MainActivity")
    if (main) {
      main["meta-data"] = (main["meta-data"] ?? []).filter((m) => m.$["android:name"] !== "android.app.shortcuts")
      main["meta-data"].push({ $: { "android:name": "android.app.shortcuts", "android:resource": "@xml/aura_shortcuts" } })
    }
    return cfg
  })
}

// Build reel (03/10) : lu par Aura.kt (BuildInfo) dans ApplicationInfo.metaData
const BUILD_META = "dev.aura.mobile.BUILD_ID"

function withBuildInfo(config) {
  const build = String(config.extra?.buildId ?? "")
  return withAndroidManifest(config, (cfg) => {
    const app = cfg.modResults.manifest.application?.[0]
    if (app && build) {
      app["meta-data"] = (app["meta-data"] ?? []).filter((m) => m.$["android:name"] !== BUILD_META)
      app["meta-data"].push({ $: { "android:name": BUILD_META, "android:value": build } })
    }
    return cfg
  })
}

module.exports = function withAuraAndroid(config) {
  return withBuildInfo(withShortcuts(withArm64Only(withReleaseSigning(config))))
}
