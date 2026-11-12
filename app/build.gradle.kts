plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Permanent release key: Gradle properties (~/.gradle/gradle.properties on the owner's Mac)
// or environment variables (CI secrets). Without them, release builds use the debug key.
fun signingValue(property: String, env: String): String? =
    providers.gradleProperty(property).orElse(providers.environmentVariable(env)).orNull?.takeIf { it.isNotBlank() }

val releaseKeystore = signingValue("shining.keystore", "SHINING_KEYSTORE")

android {
    namespace = "com.arva.shining"
    compileSdk = 37

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingValue("shining.storePassword", "SHINING_STORE_PASSWORD")
                keyAlias = signingValue("shining.keyAlias", "SHINING_KEY_ALIAS")
                keyPassword = signingValue("shining.keyPassword", "SHINING_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.arva.shining"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.6.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":tuya"))

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")

    testImplementation("junit:junit:4.13.2")
}

// Produce an informatively named copy of each built APK, e.g. Shining-1.4.0.apk, under
// build/named-apk/. Works for local builds and CI, independent of the AGP output API.
tasks.matching { it.name == "assembleRelease" || it.name == "assembleDebug" }.configureEach {
    val type = if (name.contains("Release")) "Release" else "Debug"
    finalizedBy("nameApk$type")
}
listOf("release", "debug").forEach { type ->
    val cap = type.replaceFirstChar { it.uppercase() }
    tasks.register<Copy>("nameApk$cap") {
        from(layout.buildDirectory.dir("outputs/apk/$type")) { include("app-$type.apk") }
        into(layout.buildDirectory.dir("named-apk"))
        val suffix = if (type == "debug") "-debug" else ""
        rename { "Shining-${android.defaultConfig.versionName}$suffix.apk" }
    }
}
