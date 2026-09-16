package org.qinda.deck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.qinda.deck.ui.AppRoot
import org.qinda.deck.ui.QindaDeckTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as DeckApp).repo
        setContent {
            QindaDeckTheme {
                AppRoot(repo)
            }
        }
    }
}
