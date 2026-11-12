plugins {
    // AGP 9 compiles Kotlin in Android modules itself ("built-in Kotlin"); no kotlin-android plugin.
    id("com.android.application") version "9.5.1" apply false
    // Kotlin version for the pure-JVM :tuya module and the Compose compiler (also lifts AGP's bundled KGP).
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
