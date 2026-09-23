package mk.kanta.app.core.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import mk.kanta.app.core.data.di.ApplicationScope
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.remote.dto.ProfileDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-in user's own `profiles` row, kept current across the app.
 *
 * Screens use it for one decision each — chiefly whether the hidden Admin row
 * exists (§4.6: `role = 'admin'`, set by hand in Supabase). It is a display hint
 * only: every admin RPC checks the role again on the server, so a stale or
 * tampered value here opens nothing.
 */
@Singleton
class CurrentProfile @Inject constructor(
    private val auth: AuthRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _profile = MutableStateFlow<ProfileDto?>(null)
    val profile: StateFlow<ProfileDto?> = _profile.asStateFlow()

    init {
        auth.authState
            .onEach { state ->
                when (state) {
                    is AuthState.SignedIn -> reload()
                    AuthState.SignedOut -> _profile.value = null
                    AuthState.Unknown -> Unit
                }
            }
            .launchIn(scope)
    }

    /** After sign-in or a profile edit. A failed load keeps what we had. */
    fun refresh() {
        scope.launch { reload() }
    }

    private suspend fun reload() {
        val result = auth.loadProfile()
        if (result is KantaResult.Success) _profile.value = result.data
    }
}
