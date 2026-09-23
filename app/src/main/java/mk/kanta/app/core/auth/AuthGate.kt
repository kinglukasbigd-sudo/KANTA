package mk.kanta.app.core.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import mk.kanta.app.core.data.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that decides "do it now" or "sign in first, then do it" (§4.2).
 *
 * Callers never check auth themselves; they hand the gate a [PendingAction].
 * Signed in, the action lands on [ready] immediately. Signed out, it is written to
 * disk, the login sheet is requested, and the same action lands on [ready] once
 * sign-in finishes — including after the process was killed while the user was in
 * their email app.
 *
 * [ready] is consumed, not observed: whoever handles an action calls [consume], so
 * a recomposition or a second collector cannot run it twice.
 */
@Singleton
class AuthGate @Inject constructor(
    private val auth: AuthRepository,
    private val dataStore: DataStore<Preferences>,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
    private val pendingKey = stringPreferencesKey("pending_action")

    private val _loginRequested = MutableStateFlow(false)
    val loginRequested: StateFlow<Boolean> = _loginRequested.asStateFlow()

    private val _ready = MutableStateFlow<PendingAction?>(null)
    val ready: StateFlow<PendingAction?> = _ready.asStateFlow()

    fun request(action: PendingAction) {
        if (auth.isSignedIn) {
            _ready.value = action
            return
        }
        scope.launch {
            val stored = StoredPendingAction(action, System.currentTimeMillis())
            dataStore.edit {
                it[pendingKey] = json.encodeToString(StoredPendingAction.serializer(), stored)
            }
        }
        _loginRequested.value = true
    }

    /** Profile's "Sign in" button: log in with nothing to continue afterwards. */
    fun requestLoginOnly() {
        _loginRequested.value = true
    }

    /** Called by the login sheet once the whole flow — code AND name — is done. */
    fun onSignInCompleted() {
        _loginRequested.value = false
        scope.launch {
            val stored = readStored()
            clearStored()
            // An intent older than a code's lifetime is stale: the user has moved
            // on, and replaying it hours later would be a surprise, not a service.
            val fresh = stored?.takeIf {
                System.currentTimeMillis() - it.requestedAtMillis <
                    AuthErrorMapper.OTP_EXPIRY_SECONDS * 1_000
            }
            _ready.value = fresh?.action
        }
    }

    /** The user closed the sheet. The intent is dropped, as they asked. */
    fun onLoginDismissed() {
        _loginRequested.value = false
        scope.launch { clearStored() }
    }

    fun consume(action: PendingAction) {
        if (_ready.value == action) _ready.value = null
    }

    private suspend fun readStored(): StoredPendingAction? {
        val raw = dataStore.data.first()[pendingKey] ?: return null
        return runCatching { json.decodeFromString(StoredPendingAction.serializer(), raw) }.getOrNull()
    }

    private suspend fun clearStored() {
        dataStore.edit { it.remove(pendingKey) }
    }
}
