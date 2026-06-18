pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TetoNova"
// Deploy helper: `-PserverOnly` builds just the JVM scraper stack (core:scraper + server:scraper),
// skipping the Android modules so the server can be built on a box without the Android SDK.
val serverOnly = startParameter.projectProperties.containsKey("serverOnly")
if (!serverOnly) {
    include(":app")
    include(":core:model")
    include(":core:designsystem")
}
include(":core:scraper")
include(":server:scraper")
