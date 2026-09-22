// Build racine : uniquement la déclaration des plugins (versions centralisées ici).
// AGP 8.7.x exige Gradle 8.9+ et JDK 17. Kotlin 2.0+ : le compilateur Compose est un plugin Gradle.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
