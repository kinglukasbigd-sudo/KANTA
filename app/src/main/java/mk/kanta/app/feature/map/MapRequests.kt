package mk.kanta.app.feature.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mk.kanta.app.core.location.LatLon
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Show this on the map" from another screen — the profile's report and
 * suggestion rows (§4.5 screen 11: "tap → container detail"). The asking screen
 * pops back to the map; the map takes the request once and opens it there, with
 * the camera on it.
 */
@Singleton
class MapRequests @Inject constructor() {

    data class Target(val id: String, val at: LatLon?)

    private val _container = MutableStateFlow<Target?>(null)
    val container: StateFlow<Target?> = _container.asStateFlow()

    private val _suggestion = MutableStateFlow<Target?>(null)
    val suggestion: StateFlow<Target?> = _suggestion.asStateFlow()

    fun showContainer(id: String, at: LatLon?) {
        _container.value = Target(id, at)
    }

    fun showSuggestion(id: String, at: LatLon?) {
        _suggestion.value = Target(id, at)
    }

    fun takeContainer(): Target? = _container.value.also { _container.value = null }

    fun takeSuggestion(): Target? = _suggestion.value.also { _suggestion.value = null }
}
