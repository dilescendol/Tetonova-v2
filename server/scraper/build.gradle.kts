plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Server-side scraper service: wraps the shared core:scraper parser behind a tiny HTTP API that the
// PHP panel's ScraperProxy forwards to (config.scraper.base_url). Pure JVM, runs on the 56 GB box.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.tetonova.server.scraper.MainKt")
    applicationName = "tn-scraper"
}

base { archivesName.set("tn-scraper") } // distinct jar name (avoids clash with :core:scraper)

dependencies {
    implementation(project(":core:scraper"))
    implementation(libs.okhttp)                 // fetch the panel's /api/v1/sources for the source map
    implementation(libs.org.json)               // runtime JSON (core:scraper has it compileOnly)
    implementation(libs.kotlinx.coroutines.core)
}
