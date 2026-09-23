package mk.kanta.app.core.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Something a signed-out user tried to do (spec §4.2: Full / Report / Suggest /
 * vote, plus Me too and It's been emptied from the detail sheet).
 *
 * It is plain serialisable data rather than a lambda on purpose: signing in means
 * leaving the app to read an email, and on a phone short on memory the process can
 * be killed while the user is away. The action is written to disk, so coming back
 * still ends with the user doing exactly what they set out to do (§4.2: "never
 * lose their intent").
 */
@Serializable
sealed interface PendingAction {

    /** Full (`presetFull = true`) or Report from the sheet (§4.1, §4.3). */
    @Serializable
    @SerialName("open_report")
    data class OpenReport(val presetFull: Boolean) : PendingAction

    /** Suggest from the sheet (§4.4). */
    @Serializable
    @SerialName("open_suggest")
    data object OpenSuggest : PendingAction

    /** Me too / It's been emptied from the container detail (§4.1, §5.1). */
    @Serializable
    @SerialName("confirm_report")
    data class ConfirmReport(
        val containerId: String,
        val reportId: String,
        /** `me_too` or `resolved`, the values confirm_report() accepts. */
        val kind: String,
    ) : PendingAction

    /** Vote on a suggestion (§5.3). */
    @Serializable
    @SerialName("vote")
    data class Vote(val suggestionId: String) : PendingAction
}

/** What is stored on disk: the action plus when it was requested. */
@Serializable
internal data class StoredPendingAction(
    val action: PendingAction,
    val requestedAtMillis: Long,
)
