package mk.kanta.app.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mk.kanta.app.R
import mk.kanta.app.core.auth.AuthState
import mk.kanta.app.core.auth.Municipalities
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaBanner
import mk.kanta.app.core.designsystem.component.KantaBannerTone
import mk.kanta.app.core.designsystem.component.KantaCard
import mk.kanta.app.core.designsystem.component.KantaChip
import mk.kanta.app.core.designsystem.component.KantaEmptyState
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaListRowSkeleton
import mk.kanta.app.core.designsystem.component.KantaPageTitle
import mk.kanta.app.core.designsystem.component.KantaPrimaryButton
import mk.kanta.app.core.designsystem.component.KantaSecondaryButton
import mk.kanta.app.core.designsystem.component.KantaTextField
import mk.kanta.app.core.designsystem.component.KantaTopBar

@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onAccountDeleted: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.accountDeleted) { if (state.accountDeleted) onAccountDeleted() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            KantaTopBar(onBack = onBack)

            when (val auth = state.auth) {
                AuthState.Unknown -> Column(Modifier.padding(top = Spacing.xl)) {
                    KantaListRowSkeleton()
                    KantaListRowSkeleton()
                }

                AuthState.SignedOut -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    KantaEmptyState(
                        title = stringResource(R.string.profile_signed_out_title),
                        message = stringResource(R.string.profile_signed_out_message),
                        icon = KantaIcons.Profile,
                        action = {
                            KantaPrimaryButton(
                                text = stringResource(R.string.profile_sign_in),
                                onClick = viewModel::signIn,
                            )
                        },
                    )
                }

                is AuthState.SignedIn -> SignedInProfile(
                    state = state,
                    onNameChange = viewModel::onNameChange,
                    onMunicipality = viewModel::onMunicipalitySelected,
                    onSave = viewModel::save,
                    onSignOut = viewModel::signOut,
                    onDelete = viewModel::askDelete,
                )
            }
        }
    }

    if (state.confirmDelete) {
        DeleteAccountDialog(
            deleting = state.deleting,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignedInProfile(
    state: ProfileUiState,
    onNameChange: (String) -> Unit,
    onMunicipality: (Int) -> Unit,
    onSave: () -> Unit,
    onSignOut: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        KantaPageTitle(
            title = stringResource(R.string.profile_title),
            eyebrow = state.email,
        )
        Column(Modifier.padding(horizontal = Spacing.screenHorizontal)) {
            if (state.loadingProfile) {
                KantaListRowSkeleton()
            } else {
                KantaCard(Modifier.fillMaxWidth()) {
                    KantaTextField(
                        value = state.displayName,
                        onValueChange = onNameChange,
                        label = stringResource(R.string.login_name_label),
                        enabled = !state.saving,
                    )
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        text = stringResource(R.string.login_name_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = KantaTheme.colors.onSurfaceMuted,
                    )

                    Spacer(Modifier.height(Spacing.l))
                    Text(
                        text = stringResource(R.string.profile_municipality).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = KantaTheme.colors.onSurfaceMuted,
                    )
                    Spacer(Modifier.height(Spacing.s))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        Municipalities.all.forEach { m ->
                            KantaChip(
                                label = m.localizedName(),
                                selected = state.municipalityId == m.id,
                                onClick = { onMunicipality(m.id) },
                            )
                        }
                    }
                }

                state.error?.let {
                    Spacer(Modifier.height(Spacing.m))
                    KantaBanner(text = stringResource(it.messageRes), tone = KantaBannerTone.ERROR)
                }

                Spacer(Modifier.height(Spacing.xl))
                KantaPrimaryButton(
                    text = stringResource(if (state.justSaved) R.string.profile_saved else R.string.profile_save),
                    onClick = onSave,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(Spacing.xl))
            KantaCard(Modifier.fillMaxWidth()) {
                KantaSecondaryButton(
                    text = stringResource(R.string.profile_sign_out),
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.s))
                // Deliberately quiet: available and findable (Play Store requires it),
                // never the loudest thing on the screen.
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.profile_delete_account),
                        style = MaterialTheme.typography.labelLarge,
                        color = KantaTheme.colors.error,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

/** Brief: "confirms with an in-app dialog". Says exactly what happens to their data (§8). */
@Composable
private fun DeleteAccountDialog(
    deleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        shape = KantaShape.card,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.profile_delete_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                stringResource(R.string.profile_delete_message),
                style = MaterialTheme.typography.bodyLarge,
                color = KantaTheme.colors.onSurfaceMuted,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !deleting) {
                Text(
                    stringResource(if (deleting) R.string.profile_deleting else R.string.profile_delete_confirm),
                    color = KantaTheme.colors.error,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}
