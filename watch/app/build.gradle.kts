plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
}

// Signature release : tout vient de l'environnement (voir scripts/build.sh).
// Jamais de mot de passe dans un fichier suivi par git.
val keystorePath: String? = System.getenv("AURA_KEYSTORE_PATH")
val keystorePass: String? = System.getenv("AURA_KEYSTORE_PASS")
val keyAliasName: String = System.getenv("AURA_KEY_ALIAS") ?: "aura"
val hasReleaseKey = !keystorePath.isNullOrBlank() && !keystorePass.isNullOrBlank()

android {
  namespace = "dev.aura.mobile"
  compileSdk = 36

  defaultConfig {
    // Identique à l'app téléphone : obligatoire pour le Wearable Data Layer (protocole interne).
    applicationId = "dev.aura.mobile"
    minSdk = 30
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
  }

  signingConfigs {
    create("release") {
      if (hasReleaseKey) {
        storeFile = file(keystorePath!!)
        storePassword = keystorePass
        keyAlias = keyAliasName
        keyPassword = System.getenv("AURA_KEY_PASS") ?: keystorePass
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      // PAS de shrink des ressources : l'APK du 26/09 21:17 n'avait plus AUCUN array, donc plus la capability
      // Data Layer `aura_watch` (res/values/wear.xml, lue par Play services, jamais referencee par le code) :
      // le tools:keep du fichier values n'a pas suffi avec AGP 9. Sans elle, le S22 ne voit jamais la montre
      // (pas de carte de confirmation, pas d'actions watch_*). Gain du shrink : quelques Ko.
      isShrinkResources = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (hasReleaseKey) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  testOptions {
    unitTests.isReturnDefaultValues = true
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  // Des dépendances Play services tirent fragment 1.2 ; ActivityResult exige >= 1.3.
  implementation(libs.androidx.fragment)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.process)

  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.compose.navigation)

  implementation(libs.androidx.wear.input)
  implementation(libs.wear.tiles)
  implementation(libs.wear.protolayout)
  implementation(libs.wear.protolayout.expression)
  implementation(libs.wear.complications.ktx)

  implementation(libs.play.services.wearable)
  implementation(libs.health.services)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.concurrent.futures.ktx)

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
}
