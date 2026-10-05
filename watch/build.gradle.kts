plugins {
  alias(libs.plugins.android.application) apply false
  // AGP 9 embarque Kotlin : on ne l'applique pas au module, on fixe seulement sa version.
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.kotlin.serialization) apply false
}
