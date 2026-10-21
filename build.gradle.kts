// Fichier de build "racine" : il ne fait que déclarer les plugins utilisés par
// les modules, sans les appliquer ici (apply false). Le vrai travail se fait
// dans app/build.gradle.kts.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
