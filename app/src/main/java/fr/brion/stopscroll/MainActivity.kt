package fr.brion.stopscroll

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import fr.brion.stopscroll.ui.StopScrollApp

/**
 * Activity = un écran Android. Avec Compose, l'Activity ne fait presque rien :
 * elle délègue tout l'affichage à StopScrollApp() via setContent { }.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dessine l'app sous les barres système (le Scaffold gère les marges).
        enableEdgeToEdge()
        setContent { StopScrollApp() }
    }
}
