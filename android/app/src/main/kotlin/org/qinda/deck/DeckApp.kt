package org.qinda.deck

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.qinda.deck.data.DeckRepository

/**
 * The repository outlives every screen, because the control connection should
 * survive rotation and navigation: dropping the socket on a configuration change
 * would make the deck unusable as a deck.
 */
class DeckApp : Application() {

    val scope = CoroutineScope(SupervisorJob())
    lateinit var repo: DeckRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = DeckRepository(this, scope)
    }
}
