package mk.kanta.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mk.kanta.app.core.data.prefs.SettingsRepository
import mk.kanta.app.core.data.prefs.ThemeMode
import javax.inject.Inject

/**
 * Holds the app-wide theme choice so the whole Compose tree recomposes when the user changes
 * it in Settings (spec §2 "Theme": follows system, manual override).
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settings.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        // SYSTEM until the stored value arrives — matches the platform default, so there is no
        // visible flip on a cold start for users who never changed it.
        initialValue = ThemeMode.SYSTEM,
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }
}
