plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure-JVM (no Android) so the same parser powers both the Android app's live-fallback AND the
// server-side scraper service. Android deps are abstracted behind the ChallengeSolver hook.
// Target 17 with the running JDK (Android Studio JBR) — no toolchain provisioning.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

base { archivesName.set("core-scraper") } // distinct jar name (avoids clash with :server:scraper)

dependencies {
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.coroutines.core)
    // org.json is provided by the Android platform at runtime (and by the server's own classpath),
    // so keep it compile-only here to avoid a duplicate-class clash when the app bundles this module.
    compileOnly(libs.org.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.org.json) // tests parse JSON fixtures directly (compile + runtime)
}
