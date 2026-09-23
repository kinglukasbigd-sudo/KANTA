package mk.kanta.app.core.auth

import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mk.kanta.app.core.data.di.ApplicationScope
import mk.kanta.app.core.data.di.IoDispatcher
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.remote.dto.ProfileDto
import mk.kanta.app.core.network.SupabaseClientHolder
import javax.inject.Inject
import javax.inject.Singleton

/** Who is using the app right now. */
sealed interface AuthState {
    /** The stored session is still being loaded and, if needed, refreshed. */
    data object Unknown : AuthState

    data object SignedOut : AuthState

    data class SignedIn(val userId: String, val email: String?) : AuthState
}

/**
 * Supabase email one-time-code sign-in (spec §2 "Auth", §4.2).
 *
 * Sessions persist encrypted ([EncryptedSessionManager]) and refresh themselves
 * (`alwaysAutoRefresh`, configured in SupabaseModule), so this class never handles
 * tokens directly — it asks for codes, checks them, and reports who is signed in.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val supabase: SupabaseClientHolder,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope scope: CoroutineScope,
) {

    val authState: StateFlow<AuthState> =
        if (!supabase.isConfigured) {
            // No backend: everyone is signed out, and sign-in will say why.
            MutableStateFlow(AuthState.SignedOut)
        } else {
            supabase.client.auth.sessionStatus
                .map { status ->
                    when (status) {
                        is SessionStatus.Authenticated -> AuthState.SignedIn(
                            userId = status.session.user?.id.orEmpty(),
                            email = status.session.user?.email,
                        )
                        is SessionStatus.NotAuthenticated -> AuthState.SignedOut
                        // A failed refresh (offline) is not a sign-out: the session
                        // is kept and retried, so the user stays signed in.
                        is SessionStatus.RefreshFailure -> currentOrUnknown()
                        else -> AuthState.Unknown
                    }
                }
                .stateIn(scope, SharingStarted.Eagerly, AuthState.Unknown)
        }

    val isSignedIn: Boolean get() = authState.value is AuthState.SignedIn

    private fun currentOrUnknown(): AuthState =
        supabase.client.auth.currentUserOrNull()
            ?.let { AuthState.SignedIn(it.id, it.email) }
            ?: AuthState.Unknown

    // -----------------------------------------------------------------------------------------
    // Sign-in
    // -----------------------------------------------------------------------------------------

    /** Sends (or re-sends) a 6-digit code. Creates the account on first use. */
    suspend fun sendCode(email: String): KantaResult<Unit> = guarded(secondsSinceCodeSent = null) {
        supabase.client.auth.signInWith(OTP) {
            this.email = email
            createUser = true
        }
    }

    suspend fun verifyCode(
        email: String,
        code: String,
        secondsSinceCodeSent: Long,
    ): KantaResult<Unit> = guarded(secondsSinceCodeSent) {
        supabase.client.auth.verifyEmailOtp(type = OtpType.Email.EMAIL, email = email, token = code)
    }

    // -----------------------------------------------------------------------------------------
    // Profile (§4.2: display name + optional municipality)
    // -----------------------------------------------------------------------------------------

    suspend fun loadProfile(): KantaResult<ProfileDto?> = guarded(null) {
        val uid = supabase.client.auth.currentUserOrNull()?.id ?: return@guarded null
        supabase.client.postgrest.from("profiles")
            .select { filter { eq("id", uid) } }
            .decodeSingleOrNull<ProfileDto>()
    }

    /**
     * Writes only the columns RLS lets a user change (§6: display_name,
     * municipality_id, lang). Built as a JsonObject so an unset field is omitted
     * rather than sent as null and wiping what was there.
     */
    suspend fun updateProfile(displayName: String, municipalityId: Int?): KantaResult<Unit> =
        guarded(null) {
            val uid = supabase.client.auth.currentUserOrNull()?.id
                ?: throw IllegalStateException("not signed in (KA011)")
            supabase.client.postgrest.from("profiles").update(
                buildJsonObject {
                    put("display_name", displayName)
                    put("municipality_id", municipalityId)
                },
            ) { filter { eq("id", uid) } }
        }

    // -----------------------------------------------------------------------------------------
    // Leaving
    // -----------------------------------------------------------------------------------------

    suspend fun signOut(): KantaResult<Unit> = guarded(null) {
        // LOCAL scope: signing out on this phone should not sign the user out
        // everywhere else, and it works offline.
        supabase.client.auth.signOut(SignOutScope.LOCAL)
    }

    /**
     * §8 / Play Store: delete the account. The server anonymises reports and
     * removes the user (delete_my_account, 0012). The local session is then
     * dropped whether or not the network sign-out succeeds — the user no longer
     * exists, so there is nothing to sign out of remotely.
     */
    suspend fun deleteAccount(): KantaResult<Unit> = guarded(null) {
        // Same call shape as every RPC in KantaRepository: an explicit (empty) body.
        supabase.client.postgrest.rpc("delete_my_account", buildJsonObject { })
        runCatching { supabase.client.auth.signOut(SignOutScope.LOCAL) }
        Unit
    }

    // -----------------------------------------------------------------------------------------

    private suspend fun <T> guarded(
        secondsSinceCodeSent: Long?,
        block: suspend () -> T,
    ): KantaResult<T> {
        if (!supabase.isConfigured) return KantaResult.Failure(KantaError.BackendNotConfigured)
        return withContext(io) {
            try {
                KantaResult.Success(block())
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                KantaResult.Failure(AuthErrorMapper.fromThrowable(t, secondsSinceCodeSent))
            }
        }
    }
}
