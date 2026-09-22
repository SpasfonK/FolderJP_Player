package com.n7folder.player.ui.nav

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Écrans de la bibliothèque, empilés : le dernier est affiché. */
sealed interface Route {
    /** Nuage d'artistes zoomable (accueil). */
    data object Cloud : Route

    /** Grille de pochettes d'un artiste. */
    data class Artist(val key: String) : Route

    /** Liste des pistes d'un album. */
    data class Album(val artistKey: String, val albumKey: String) : Route

    /** Explorateur de dossiers ; [path] = [id de la source, dossier, sous-dossier…]. */
    data class Explorer(val path: List<String>) : Route

    /** Gestion des dossiers sources. */
    data object Sources : Route
}

/** Panneaux plein écran du lecteur, au-dessus de la bibliothèque. */
enum class PlayerPanel { NONE, NOW_PLAYING, QUEUE, EQUALIZER }

data class NavState(
    val stack: List<Route> = listOf(Route.Cloud),
    val panel: PlayerPanel = PlayerPanel.NONE
) {
    val route: Route get() = stack.last()
    val canGoBack: Boolean get() = panel != PlayerPanel.NONE || stack.size > 1
}

class NavViewModel : ViewModel() {

    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state.asStateFlow()

    fun push(route: Route) {
        _state.update { current ->
            if (current.route == route) current else current.copy(stack = current.stack + route)
        }
    }

    /** Revient à l'accueil (nuage), en vidant la pile. */
    fun goHome() {
        _state.update { it.copy(stack = listOf(Route.Cloud)) }
    }

    /** Remonte jusqu'à [route] si elle est dans la pile (fil d'Ariane), sinon l'empile. */
    fun popTo(route: Route) {
        _state.update { current ->
            val index = current.stack.lastIndexOf(route)
            if (index >= 0) current.copy(stack = current.stack.subList(0, index + 1)) else current.copy(stack = current.stack + route)
        }
    }

    fun openPanel(panel: PlayerPanel) {
        _state.update { it.copy(panel = panel) }
    }

    fun closePanels() {
        _state.update { it.copy(panel = PlayerPanel.NONE) }
    }

    /** Retour système : ferme d'abord les panneaux du lecteur, puis remonte la pile d'écrans. */
    fun back() {
        _state.update { current ->
            when (current.panel) {
                PlayerPanel.EQUALIZER, PlayerPanel.QUEUE -> current.copy(panel = PlayerPanel.NOW_PLAYING)
                PlayerPanel.NOW_PLAYING -> current.copy(panel = PlayerPanel.NONE)
                PlayerPanel.NONE ->
                    if (current.stack.size > 1) current.copy(stack = current.stack.dropLast(1)) else current
            }
        }
    }
}
