package org.qinda.deck.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Screens, and a back stack.
 *
 * Hand-rolled rather than navigation-compose: routes here carry typed payloads
 * (a whole profile under edit, a macro plus where to write it back), and encoding
 * those into string routes would mean serialising editor state through the URL.
 */
sealed interface Screen {
    data object Connect : Screen
    data object Deck : Screen
    data object Trackpad : Screen
    data object Voice : Screen
    data object Profiles : Screen
    data object Displays : Screen
    data object Bluetooth : Screen
    data object Sd : Screen
    data object Settings : Screen

    data class ProfileEditor(val slug: String) : Screen
    data class ButtonEditor(val slug: String, val page: Int, val index: Int) : Screen
    /** Editing a macro reached from a button; `null` button means a profile-level macro. */
    data class MacroEditor(val slug: String, val page: Int, val index: Int) : Screen
    data class RemapEditor(val addr: String) : Screen
    data class BindingEditor(val addr: String, val bindingIndex: Int) : Screen
    data class RemapMacroEditor(val addr: String, val bindingIndex: Int) : Screen
}

/** Tabs shown in the bottom bar; everything else is pushed on top. */
val bottomTabs = listOf(Screen.Deck, Screen.Trackpad, Screen.Voice, Screen.Profiles, Screen.Settings)

class Navigator(start: Screen = Screen.Connect) {
    private val stack = mutableStateListOf(start)

    var current by mutableStateOf(start)
        private set

    val canGoBack: Boolean get() = stack.size > 1

    fun push(screen: Screen) {
        stack.add(screen)
        current = screen
    }

    /** Switch the root tab, discarding anything pushed above it. */
    fun selectTab(screen: Screen) {
        stack.clear()
        stack.add(screen)
        current = screen
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        current = stack.last()
        return true
    }
}
