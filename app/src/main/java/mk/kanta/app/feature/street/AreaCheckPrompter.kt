package mk.kanta.app.feature.street

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import mk.kanta.app.core.data.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When the "Are all containers near you on the map?" sheet may appear (§4.6).
 *
 * Pure, so every branch is unit tested. The server owns the "30 days / area
 * recently checked" rule (`should_prompt_area_check`); this only decides
 * whether it is a good MOMENT to show anything at all.
 */
object AreaCheckPromptPolicy {

    enum class Decision {
        /** Not now — try again when something changes. */
        Wait,

        /** Already shown (or asked) this session: never again until the app restarts. */
        Done,

        /** First login: show it without asking the server. */
        ShowNow,

        /** Ask `should_prompt_area_check` for this spot. */
        AskServer,
    }

    fun decide(
        signedIn: Boolean,
        /** §4.2: no sign-in sheet, no intent in flight, no other sheet open. */
        idle: Boolean,
        hasLocation: Boolean,
        handledThisSession: Boolean,
        firstLoginDue: Boolean,
    ): Decision = when {
        handledThisSession -> Decision.Done
        !signedIn || !idle || !hasLocation -> Decision.Wait
        firstLoginDue -> Decision.ShowNow
        else -> Decision.AskServer
    }
}

/**
 * The two facts [AreaCheckPromptPolicy] needs that outlive a screen.
 *
 * - **First login due** is on disk: a brand-new user who signs in to send a
 *   report may have the app killed while taking the photo, and should still be
 *   asked once they are back on the map.
 * - **Handled this session** is in memory on purpose — "never more than once per
 *   app session" (§4.6) means a fresh process may ask again.
 */
@Singleton
class AreaCheckPrompter @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val key = booleanPreferencesKey("area_check_first_login_due")

    private val _firstLoginDue = MutableStateFlow(false)
    val firstLoginDue: StateFlow<Boolean> = _firstLoginDue.asStateFlow()

    private val _handledThisSession = MutableStateFlow(false)
    val handledThisSession: StateFlow<Boolean> = _handledThisSession.asStateFlow()

    init {
        scope.launch {
            if (dataStore.data.first()[key] == true) _firstLoginDue.value = true
        }
    }

    /**
     * A new account just finished sign-in (email, code, name). Set in memory at
     * once, so the map sees it on its very next look; written to disk behind.
     */
    fun markFirstLogin() {
        _firstLoginDue.value = true
        scope.launch { dataStore.edit { it[key] = true } }
    }

    /** Shown, answered, skipped or opened by hand: done for this session. */
    fun markHandled() {
        _handledThisSession.value = true
        if (_firstLoginDue.value) {
            _firstLoginDue.value = false
            scope.launch { dataStore.edit { it.remove(key) } }
        }
    }
}
