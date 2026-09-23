package mk.kanta.app.feature.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mk.kanta.app.R
import mk.kanta.app.core.auth.Municipalities
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.MarkerColors
import mk.kanta.app.core.designsystem.Motion
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaChip
import mk.kanta.app.core.designsystem.component.KantaDragHandle
import mk.kanta.app.core.designsystem.component.KantaPrimaryButton
import mk.kanta.app.core.designsystem.rememberKantaHaptics
import mk.kanta.app.core.designsystem.tabularFigures

/**
 * App-level host: renders the sheet over whatever screen asked the [AuthGate] for
 * a sign-in. Placed once, next to the NavHost.
 */
@Composable
fun LoginSheetHost(viewModel: AuthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.open) return

    LoginSheet(
        state = state,
        onDismiss = viewModel::dismiss,
        onEmailChange = viewModel::onEmailChange,
        onSendCode = viewModel::sendCode,
        onCodeChange = viewModel::onCodeChange,
        onVerify = viewModel::verifyCode,
        onResend = viewModel::resendCode,
        onChangeEmail = viewModel::changeEmail,
        onNameChange = viewModel::onNameChange,
        onMunicipality = viewModel::onMunicipalitySelected,
        onSaveName = viewModel::saveName,
    )
}

/**
 * Sign-in sheet (spec §4.2). Calm by design (brief): one field per step, one big
 * pill button, plenty of air. Steps slide sideways so it reads as moving forward
 * through one short task rather than three dialogs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginSheet(
    state: LoginUiState,
    onDismiss: () -> Unit,
    onEmailChange: (String) -> Unit,
    onSendCode: () -> Unit,
    onCodeChange: (String) -> Unit,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onChangeEmail: () -> Unit,
    onNameChange: (String) -> Unit,
    onMunicipality: (Int?) -> Unit,
    onSaveName: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = KantaShape.bottomSheet,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { KantaDragHandle() },
    ) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                (slideInHorizontally(Motion.tweenMedium()) { it / 4 } + fadeIn(Motion.tweenMedium()))
                    .togetherWith(fadeOut(Motion.tweenFast()))
            },
            label = "loginStep",
        ) { step ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl)
                    .padding(top = Spacing.m, bottom = Spacing.xxl)
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                when (step) {
                    LoginStep.Email -> EmailStep(state, onEmailChange, onSendCode)
                    LoginStep.Code -> CodeStep(state, onCodeChange, onVerify, onResend, onChangeEmail)
                    LoginStep.Name -> NameStep(state, onNameChange, onMunicipality, onSaveName)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Steps
// ---------------------------------------------------------------------------------------------

@Composable
private fun EmailStep(
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onSendCode: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    StepTitle(stringResource(R.string.login_title), stringResource(R.string.login_email_subtitle))

    KantaTextField(
        value = state.email,
        onValueChange = onEmailChange,
        label = stringResource(R.string.login_email_label),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Send,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onSend = { if (state.canSendCode) onSendCode() }),
        isError = state.error != null,
        enabled = !state.loading,
        modifier = Modifier.focusRequester(focus),
    )
    ErrorLine(state.error)

    Spacer(Modifier.height(Spacing.xl))
    KantaPrimaryButton(
        text = stringResource(if (state.loading) R.string.login_sending else R.string.login_send_code),
        onClick = onSendCode,
        enabled = state.canSendCode,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CodeStep(
    state: LoginUiState,
    onCodeChange: (String) -> Unit,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onChangeEmail: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val haptics = rememberKantaHaptics()
    LaunchedEffect(Unit) { focus.requestFocus() }
    // A wrong code gets a tick so the error is felt, not only read.
    LaunchedEffect(state.error) { if (state.error != null) haptics.tick() }

    StepTitle(
        stringResource(R.string.login_code_title),
        stringResource(R.string.login_code_sent_to, state.email),
    )
    TextButton(onClick = onChangeEmail, enabled = !state.loading) {
        Text(stringResource(R.string.login_change_email), style = MaterialTheme.typography.labelLarge)
    }

    Spacer(Modifier.height(Spacing.m))
    OtpCodeField(
        code = state.code,
        onCodeChange = onCodeChange,
        isError = state.error != null,
        enabled = !state.loading,
        focusRequester = focus,
        contentDescription = stringResource(R.string.login_code_title),
        onDone = onVerify,
    )
    ErrorLine(state.error)

    Spacer(Modifier.height(Spacing.l))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state.resendSecondsLeft > 0) {
            Text(
                text = stringResource(
                    R.string.login_resend_in,
                    "0:%02d".format(state.resendSecondsLeft),
                ),
                style = MaterialTheme.typography.bodySmall.tabularFigures(),
                color = KantaTheme.colors.onSurfaceMuted,
            )
        } else {
            TextButton(onClick = onResend, enabled = !state.loading) {
                Text(stringResource(R.string.login_resend), style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    Spacer(Modifier.height(Spacing.l))
    KantaPrimaryButton(
        text = stringResource(if (state.loading) R.string.login_verifying else R.string.login_verify),
        onClick = onVerify,
        enabled = state.code.length == 6 && !state.loading,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameStep(
    state: LoginUiState,
    onNameChange: (String) -> Unit,
    onMunicipality: (Int?) -> Unit,
    onSaveName: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    StepTitle(stringResource(R.string.login_name_title), stringResource(R.string.login_name_subtitle))

    KantaTextField(
        value = state.displayName,
        onValueChange = onNameChange,
        label = stringResource(R.string.login_name_label),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { if (state.canSaveName) onSaveName() }),
        isError = state.error != null,
        enabled = !state.loading,
        modifier = Modifier.focusRequester(focus),
    )
    ErrorLine(state.error)

    // Optional, and says so: skipping it costs nothing. A plain caption rather
    // than KantaSectionHeader, whose screen-edge padding would double up inside
    // the sheet's own.
    Spacer(Modifier.height(Spacing.xl))
    Text(
        text = stringResource(R.string.login_municipality_optional).uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = KantaTheme.colors.onSurfaceMuted,
    )
    Spacer(Modifier.height(Spacing.s))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Municipalities.all.forEach { municipality ->
            KantaChip(
                label = municipality.localizedName(),
                selected = state.municipalityId == municipality.id,
                onClick = { onMunicipality(municipality.id) },
                enabled = !state.loading,
            )
        }
    }

    Spacer(Modifier.height(Spacing.xl))
    KantaPrimaryButton(
        text = stringResource(R.string.login_continue),
        onClick = onSaveName,
        enabled = state.canSaveName,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------------------------

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(Spacing.s))
    Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = KantaTheme.colors.onSurfaceMuted)
    Spacer(Modifier.height(Spacing.xl))
}

@Composable
private fun ErrorLine(error: KantaError?) {
    if (error == null) return
    Spacer(Modifier.height(Spacing.s))
    Text(
        text = stringResource(error.messageRes),
        style = MaterialTheme.typography.bodySmall,
        color = MarkerColors.Broken,
    )
}

/**
 * The one text-field style in the app: muted fill, 12dp corners, no underline
 * (§3.3: separate with tone, not lines).
 */
@Composable
fun KantaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    isError: Boolean = false,
    enabled: Boolean = true,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = KantaShape.chip,
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = KantaTheme.colors.surfaceMuted,
            unfocusedContainerColor = KantaTheme.colors.surfaceMuted,
            disabledContainerColor = KantaTheme.colors.surfaceMuted,
            errorContainerColor = KantaTheme.colors.surfaceMuted,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            focusedLabelColor = KantaTheme.colors.brand,
            cursorColor = KantaTheme.colors.brand,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
