plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tetonova.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tetonova.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Control-panel base URL for the live /api/v1/sources feed (sources + Home sections).
        // Precedence: Gradle property -> env var -> the live panel default.
        val controlPanelUrl = (project.findProperty("TETONOVA_CONTROL_PANEL_URL") as String?)
            ?: System.getenv("TETONOVA_CONTROL_PANEL_URL") ?: "https://tetonova.dilcendol.web.id"
        buildConfigField("String", "TETONOVA_CONTROL_PANEL_URL", "\"$controlPanelUrl\"")

        // OMDb (IMDB) API key for Movie/Drama detail (omdbapi.com). Override via gradle prop / env.
        val omdbKey = (project.findProperty("TETONOVA_OMDB_KEY") as String?)
            ?: System.getenv("TETONOVA_OMDB_KEY") ?: "d1f883ce"
        buildConfigField("String", "TETONOVA_OMDB_KEY", "\"$omdbKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
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

    implementation(libs.coil.compose)
    implementation(libs.androidx.datastore.preferences)

    // Data layer: registry/catalog parsing + control-panel sources API + live upstream scraping.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    debugImplementation(libs.androidx.ui.tooling)
}
