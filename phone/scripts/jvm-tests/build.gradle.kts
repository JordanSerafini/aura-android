// Harnais JVM : la logique pure d'aura-device testee sans expo prebuild ni node_modules (revue du 24/10, point 5).
// Les sources sont copiees dans build/w par run.sh ; ne rien editer ici pour ajouter un fichier pur.
plugins { kotlin("jvm") version "2.1.20" }
repositories { mavenCentral() }
dependencies {
  implementation("org.json:json:20240303")
  testImplementation("junit:junit:4.13.2")
}
sourceSets {
  main { kotlin.setSrcDirs(listOf("shim", "build/w/main")) }
  test { kotlin.setSrcDirs(listOf("build/w/test")) }
}
kotlin { jvmToolchain(21) }
tasks.test {
  workingDir = file("../../modules/aura-device/android")  // certains tests relisent les sources depuis le dossier du module
  testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT }
}
