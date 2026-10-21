// ─────────────────────────────────────────────────────────────────────────────
// app/build.gradle.kts — configuration du module "app" (notre application).
// ─────────────────────────────────────────────────────────────────────────────

plugins {
    // "Ceci est une application Android". Depuis AGP 9, ce plugin gère aussi
    // la compilation Kotlin tout seul (plus besoin du plugin kotlin-android).
    alias(libs.plugins.android.application)
    // Active le compilateur Jetpack Compose.
    alias(libs.plugins.kotlin.compose)
}

android {
    // Espace de noms du code Kotlin (classe R, BuildConfig…).
    namespace = "fr.brion.stopscroll"

    // compileSdk : version des API Android avec laquelle on compile.
    // 37 = Android 17, la version de ton Pixel 7a.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Identifiant unique de l'app sur le téléphone.
        applicationId = "fr.brion.stopscroll"
        // minSdk : version Android minimale supportée (26 = Android 8.0).
        minSdk = 26
        // targetSdk : version pour laquelle l'app est testée ; Android applique
        // les règles de comportement de cette version.
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Pas d'obfuscation/minification : app personnelle, on garde simple.
            optimization {
                enable = false
            }
            // Signée avec la clé debug d'Android Studio (~/.android/debug.keystore).
            // Suffisant pour un usage personnel, et permet d'installer la version
            // finale par-dessus la version debug sans perdre réglages ni activation.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Génère la classe BuildConfig (BuildConfig.DEBUG nous servira pour le mode debug).
        buildConfig = true
    }
}

dependencies {
    // Le BOM fixe des versions compatibles pour toutes les bibliothèques Compose.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // DataStore : enregistre les réglages dans un petit fichier privé de l'app.
    implementation(libs.androidx.datastore.preferences)

    // Outils d'aperçu Compose, inclus seulement dans la version debug.
    debugImplementation(libs.androidx.compose.ui.tooling)
}
