package mk.kanta.app.feature.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mk.kanta.app.core.auth.AuthGate
import mk.kanta.app.core.auth.AuthInput
import mk.kanta.app.core.auth.AuthRepository
import mk.kanta.app.core.auth.PendingAction
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaRepository
import mk.kanta.app.core.data.remote.KantaResult
import javax.inject.Inject

enum class LoginStep { Email, Code, Name }

data class LoginUiState(
    val open: Boolean = false,
    val step: LoginStep = LoginStep.Email,
    val email: String = "",
    val code: String = "",
    val displayName: String = "",
    val municipalityId: Int? = null,
    val loading: Boolean = false,
    val error: KantaError? = null,
    /** Seconds until "Resend code" is allowed again (brief: 30 s). */
    val resendSecondsLeft: Int = 0,
) {
    val canSendCode: Boolean get() = AuthInput.isPlausibleEmail(email) && !loading
    val canSaveName: Boolean get() = AuthInput.isValidDisplayName(displayName) && !loading
}

/**
 * Drives the sign-in sheet (spec §4.2): email → 6-digit code → display name.
 *
 * Signing in means leaving the app to read an email, which is exactly when Android
 * kills background apps. So the parts that cannot be recomputed — whether the sheet
 * is open, the step, the address, when the code was sent — live in
 * [SavedStateHandle] and survive process death. Coming back lands on the code step
 * with the right address and a correct resend countdown, not an empty form.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val auth: AuthRepository,
    private val gate: AuthGate,
    private val kanta: KantaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LoginUiState(
            open = savedState[KEY_OPEN] ?: false,
            step = savedState.get<String>(KEY_STEP)
                ?.let { name -> LoginStep.entries.firstOrNull { it.name == name } }
                ?: LoginStep.Email,
            email = savedState[KEY_EMAIL] ?: "",
        ),
    )
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private var countdown: Job? = null

    /** Snackbar-style one-line results for actions the NavHost executes (Vote). */
    private val _message = MutableStateFlow<KantaError?>(null)
    val message: StateFlow<KantaError?> = _message.asStateFlow()

    init {
        // Any screen asks the gate; the gate asks us to show the sheet.
        viewModelScope.launch {
            gate.loginRequested.collect { requested ->
                if (requested && !_state.value.open) open()
            }
        }
        if (_state.value.open && _state.value.step == LoginStep.Code) startCountdown()
    }

    // -----------------------------------------------------------------------------------------
    // Step 1 — email
    // -----------------------------------------------------------------------------------------

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }

    fun sendCode() {
        val email = AuthInput.normalizeEmail(_state.value.email)
        if (!AuthInput.isPlausibleEmail(email)) {
            _state.update { it.copy(error = KantaError.InvalidEmail) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = auth.sendCode(email)) {
                is KantaResult.Success -> {
                    savedState[KEY_EMAIL] = email
                    savedState[KEY_SENT_AT] = System.currentTimeMillis()
                    setStep(LoginStep.Code)
                    _state.update { it.copy(email = email, code = "", loading = false) }
                    startCountdown()
                }
                is KantaResult.Failure -> _state.update { it.copy(loading = false, error = result.error) }
                KantaResult.Loading -> Unit
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Step 2 — code
    // -----------------------------------------------------------------------------------------

    fun onCodeChange(raw: String) {
        val code = AuthInput.sanitizeCode(raw)
        _state.update { it.copy(code = code, error = null) }
        // Auto-submit on the sixth digit: typing, pasting and autofill all end here.
        if (code.length == AuthInput.CODE_LENGTH && !_state.value.loading) verifyCode()
    }

    fun verifyCode() {
        val current = _state.value
        if (current.code.length != AuthInput.CODE_LENGTH) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = auth.verifyCode(current.email, current.code, secondsSinceSent())) {
                is KantaResult.Success -> afterVerified()
                is KantaResult.Failure -> _state.update {
                    // Clear the boxes on a wrong code so the next attempt starts clean.
                    it.copy(loading = false, error = result.error, code = "")
                }
                KantaResult.Loading -> Unit
            }
        }
    }

    fun resendCode() {
        if (_state.value.resendSecondsLeft > 0) return
        sendCode()
    }

    fun changeEmail() {
        countdown?.cancel()
        setStep(LoginStep.Email)
        _state.update { it.copy(code = "", error = null, resendSecondsLeft = 0) }
    }

    /**
     * Returning users already have a name and skip straight to their action;
     * first-timers pick one (§4.2).
     */
    private suspend fun afterVerified() {
        countdown?.cancel()
        val profile = (auth.loadProfile() as? KantaResult.Success)?.data
        if (profile?.displayName.isNullOrBlank()) {
            setStep(LoginStep.Name)
            _state.update {
                it.copy(loading = false, code = "", municipalityId = profile?.municipalityId)
            }
        } else {
            finish()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Step 3 — name (+ optional municipality)
    // -----------------------------------------------------------------------------------------

    fun onNameChange(value: String) = _state.update { it.copy(displayName = value, error = null) }

    fun onMunicipalitySelected(id: Int?) = _state.update {
        // Tapping the selected one again clears it — the field is optional.
        it.copy(municipalityId = if (it.municipalityId == id) null else id)
    }

    fun saveName() {
        val name = AuthInput.normalizeDisplayName(_state.value.displayName)
        if (!AuthInput.isValidDisplayName(name)) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = auth.updateProfile(name, _state.value.municipalityId)) {
                is KantaResult.Success -> finish()
                is KantaResult.Failure -> _state.update { it.copy(loading = false, error = result.error) }
                KantaResult.Loading -> Unit
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Open / close
    // -----------------------------------------------------------------------------------------

    private fun open() {
        savedState[KEY_OPEN] = true
        _state.update { it.copy(open = true, error = null) }
    }

    /**
     * The sheet was closed by the user.
     *
     * On the email or code step that means "not now", and the intent is dropped.
     * On the name step the user is already signed in — the hard part is done — so
     * closing it still completes sign-in and continues what they started. Losing
     * their action over a skipped nickname would break §4.2's "never lose their
     * intent" for the sake of a field they can fill in later on the profile.
     */
    fun dismiss() {
        if (_state.value.step == LoginStep.Name) {
            finish()
        } else {
            reset()
            gate.onLoginDismissed()
        }
    }

    private fun finish() {
        reset()
        gate.onSignInCompleted()
    }

    private fun reset() {
        countdown?.cancel()
        listOf(KEY_OPEN, KEY_STEP, KEY_EMAIL, KEY_SENT_AT).forEach { savedState.remove<Any>(it) }
        _state.value = LoginUiState()
    }

    private fun setStep(step: LoginStep) {
        savedState[KEY_STEP] = step.name
        _state.update { it.copy(step = step) }
    }

    private fun secondsSinceSent(): Long {
        val sentAt = savedState.get<Long>(KEY_SENT_AT) ?: return 0
        return (System.currentTimeMillis() - sentAt) / 1_000
    }

    private fun startCountdown() {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            while (isActive) {
                val left = (RESEND_AFTER_SECONDS - secondsSinceSent()).coerceAtLeast(0).toInt()
                _state.update { it.copy(resendSecondsLeft = left) }
                if (left == 0) break
                delay(1_000)
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // The gate, for composables that cannot inject it directly (the NavHost).
    // -----------------------------------------------------------------------------------------

    val readyAction: StateFlow<PendingAction?> = gate.ready

    fun request(action: PendingAction) = gate.request(action)

    fun consume(action: PendingAction) = gate.consume(action)

    // -----------------------------------------------------------------------------------------
    // Actions the NavHost runs after sign-in that have no screen of their own yet
    // -----------------------------------------------------------------------------------------

    /** §5.3: one vote per user. Surfaced through [message]. */
    fun vote(suggestionId: String) {
        viewModelScope.launch {
            kanta.voteSuggestion(suggestionId).collect { result ->
                if (result is KantaResult.Failure) _message.value = result.error
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        const val RESEND_AFTER_SECONDS = 30L
        const val KEY_OPEN = "login_open"
        const val KEY_STEP = "login_step"
        const val KEY_EMAIL = "login_email"
        const val KEY_SENT_AT = "login_sent_at"
    }
}
