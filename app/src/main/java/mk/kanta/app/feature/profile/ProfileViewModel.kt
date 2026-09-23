package mk.kanta.app.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mk.kanta.app.core.auth.AuthGate
import mk.kanta.app.core.auth.AuthInput
import mk.kanta.app.core.auth.AuthRepository
import mk.kanta.app.core.auth.AuthState
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaResult
import javax.inject.Inject

data class ProfileUiState(
    val auth: AuthState = AuthState.Unknown,
    val loadingProfile: Boolean = false,
    val email: String? = null,
    /** What is saved on the server, to know whether Save has anything to do. */
    val savedName: String = "",
    val savedMunicipalityId: Int? = null,
    val displayName: String = "",
    val municipalityId: Int? = null,
    val saving: Boolean = false,
    val justSaved: Boolean = false,
    val confirmDelete: Boolean = false,
    val deleting: Boolean = false,
    val accountDeleted: Boolean = false,
    val error: KantaError? = null,
) {
    val hasChanges: Boolean
        get() = AuthInput.normalizeDisplayName(displayName) != savedName || municipalityId != savedMunicipalityId

    val canSave: Boolean
        get() = hasChanges && AuthInput.isValidDisplayName(displayName) && !saving
}

/** Profile basics (spec §4.2): name, municipality, sign out, delete account. */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val gate: AuthGate,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            auth.authState.collect { authState ->
                _state.update { it.copy(auth = authState) }
                if (authState is AuthState.SignedIn) loadProfile(authState.email)
            }
        }
    }

    private fun loadProfile(email: String?) {
        viewModelScope.launch {
            _state.update { it.copy(loadingProfile = true, email = email) }
            when (val result = auth.loadProfile()) {
                is KantaResult.Success -> {
                    val name = result.data?.displayName.orEmpty()
                    val municipality = result.data?.municipalityId
                    _state.update {
                        it.copy(
                            loadingProfile = false,
                            savedName = name,
                            savedMunicipalityId = municipality,
                            displayName = name,
                            municipalityId = municipality,
                        )
                    }
                }
                is KantaResult.Failure -> _state.update { it.copy(loadingProfile = false, error = result.error) }
                KantaResult.Loading -> Unit
            }
        }
    }

    fun signIn() = gate.requestLoginOnly()

    fun onNameChange(value: String) = _state.update { it.copy(displayName = value, justSaved = false, error = null) }

    fun onMunicipalitySelected(id: Int) = _state.update {
        it.copy(municipalityId = if (it.municipalityId == id) null else id, justSaved = false)
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        val name = AuthInput.normalizeDisplayName(current.displayName)
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            when (val result = auth.updateProfile(name, current.municipalityId)) {
                is KantaResult.Success -> _state.update {
                    it.copy(
                        saving = false,
                        justSaved = true,
                        savedName = name,
                        displayName = name,
                        savedMunicipalityId = current.municipalityId,
                    )
                }
                is KantaResult.Failure -> _state.update { it.copy(saving = false, error = result.error) }
                KantaResult.Loading -> Unit
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            val result = auth.signOut()
            if (result is KantaResult.Failure) _state.update { it.copy(error = result.error) }
        }
    }

    fun askDelete() = _state.update { it.copy(confirmDelete = true) }

    fun cancelDelete() = _state.update { it.copy(confirmDelete = false) }

    fun confirmDelete() {
        viewModelScope.launch {
            _state.update { it.copy(deleting = true, error = null) }
            when (val result = auth.deleteAccount()) {
                is KantaResult.Success -> _state.update {
                    it.copy(deleting = false, confirmDelete = false, accountDeleted = true)
                }
                is KantaResult.Failure -> _state.update {
                    it.copy(deleting = false, confirmDelete = false, error = result.error)
                }
                KantaResult.Loading -> Unit
            }
        }
    }
}
