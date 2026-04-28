pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
    }
}

@file:Suppress("ktlint:standard:property-naming")
val TRANSLATIONS_ONLY: String? by settings

rootProject.name = "portal-android"

if (TRANSLATIONS_ONLY.isNullOrBlank()) {
    include(":app")
}
include(":translations")
