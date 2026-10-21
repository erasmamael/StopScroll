// ─────────────────────────────────────────────────────────────────────────────
// settings.gradle.kts — le tout premier fichier lu par Gradle.
//
// Gradle est l'outil qui transforme le code source en APK installable.
// Ce fichier lui dit :
//   1. où télécharger les plugins (pluginManagement) ;
//   2. où télécharger les bibliothèques (dependencyResolutionManagement) ;
//   3. quels modules composent le projet (ici un seul : ":app").
// ─────────────────────────────────────────────────────────────────────────────

pluginManagement {
    repositories {
        // Dépôt de Google : plugin Android, bibliothèques AndroidX…
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

plugins {
    // Permet à Gradle de télécharger automatiquement le bon JDK si besoin.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Interdit aux modules de déclarer leurs propres dépôts : tout est centralisé ici.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "StopScroll"
include(":app")
