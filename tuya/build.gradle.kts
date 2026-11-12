plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Integration tests against tools/mock_device.py run only when these are set.
    listOf("TUYA_MOCK_PORT", "TUYA_MOCK_VERSION").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
