import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Apply Google Services ONLY when google-services.json is present. This keeps the
// build green before Firebase is set up — Google sign-in stays inert (the app is
// local-first) until the JSON is dropped into app/. See AuthManager.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// --- Build-time secret obfuscation ---------------------------------------------------
// XOR + base64 the panel domain / API keys so a decompiled release APK doesn't expose
// them as plaintext string constants. R8 minify does NOT encrypt string literals, so a
// raw buildConfigField value is trivially recoverable with `strings`/jadx. The matching
// runtime decode lives in app/.../Secrets.kt — keep secretXorKey in sync with it. This
// is obfuscation, not real crypto: it defeats casual extraction, not a determined RE.
val secretXorKey = "Tn0v4_biz_2026".toByteArray(Charsets.UTF_8)
fun obfuscateSecret(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    val out = ByteArray(bytes.size) { i ->
        (bytes[i].toInt() xor secretXorKey[i % secretXorKey.size].toInt()).toByte()
    }
    return Base64.getEncoder().encodeToString(out)
}

android {
    namespace = "com.tetonova.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tetonova.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.2"

        // App hanya berbahasa Indonesia + Inggris. Buang string locale lain yang
        // dibawa Compose/Material3 dari resources.arsc. Default strings.xml app
        // (tanpa qualifier) tetap selalu disertakan.
        resourceConfigurations += listOf("en", "in")

        // Control-panel base URL for the live /api/v1/sources feed (sources + Home sections).
        // Precedence: Gradle property -> env var -> the live panel default.
        // Obfuscated at build time (see obfuscateSecret above + Secrets.kt) so the domain
        // isn't a plaintext constant in the APK. Read via Secrets.controlPanelUrl, never BuildConfig directly.
        val controlPanelUrl = (project.findProperty("TETONOVA_CONTROL_PANEL_URL") as String?)
            ?: System.getenv("TETONOVA_CONTROL_PANEL_URL") ?: "https://tetonova.biz.id"
        buildConfigField("String", "TETONOVA_CONTROL_PANEL_URL_ENC", "\"${obfuscateSecret(controlPanelUrl)}\"")

        // OMDb (IMDB) API key for Movie/Drama detail (omdbapi.com). Override via gradle prop / env.
        // Obfuscated like the panel URL — read via Secrets.omdbKey.
        val omdbKey = (project.findProperty("TETONOVA_OMDB_KEY") as String?)
            ?: System.getenv("TETONOVA_OMDB_KEY") ?: "d1f883ce"
        buildConfigField("String", "TETONOVA_OMDB_KEY_ENC", "\"${obfuscateSecret(omdbKey)}\"")
    }

    // Release signing from user-level gradle.properties (~/.gradle/gradle.properties) — keystore
    // and passwords live OUTSIDE the repo. When the properties are absent (CI, another machine)
    // the release build still assembles, just unsigned.
    val releaseStoreFile = (project.findProperty("TETONOVA_STORE_FILE") as String?)?.let(::file)?.takeIf { it.exists() }
    if (releaseStoreFile != null) {
        signingConfigs.create("release") {
            storeFile = releaseStoreFile
            storePassword = project.findProperty("TETONOVA_STORE_PASSWORD") as String?
            keyAlias = project.findProperty("TETONOVA_KEY_ALIAS") as String?
            keyPassword = project.findProperty("TETONOVA_KEY_PASSWORD") as String?
        }
    }

    buildTypes {
        debug {
            // Keep debug on the main Firebase Android app id so Google Sign-In uses
            // the `com.tetonova.app` OAuth client/fingerprint.
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:scraper"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.window)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp) // OkHttpDataSource for IPv4-pinned Dailymotion playback

    implementation(libs.coil.compose)
    implementation(libs.androidx.datastore.preferences)

    // Data layer: registry/catalog parsing + control-panel sources API + live upstream scraping.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    // Account sync: Firebase Auth + legacy Google Sign-In (play-services-auth). Inert until
    // google-services.json is present (see the conditional plugin apply above).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.messaging)
    implementation(libs.play.services.auth)

    // WorkManager for background task scheduling (follow episode check)
    implementation(libs.androidx.work.runtime)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(kotlin("test"))
}
