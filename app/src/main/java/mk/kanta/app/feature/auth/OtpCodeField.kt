package mk.kanta.app.feature.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import mk.kanta.app.core.auth.AuthInput
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.MarkerColors
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.tabularFigures

/**
 * Six boxes for the one-time code (brief: "auto-advance boxes, paste support").
 *
 * It is ONE text field drawn as six boxes, not six fields. That is what makes the
 * behaviours come for free and correctly: typing advances because it is one
 * string growing; backspace goes back for the same reason; pasting "123 456" or a
 * whole "Your code is 123456" line fills every box at once after
 * [AuthInput.sanitizeCode]; and keyboard/Gmail code suggestions land as a single
 * value. Six separate fields each need hand-written focus juggling and still break
 * on paste.
 */
@Composable
fun OtpCodeField(
    code: String,
    onCodeChange: (String) -> Unit,
    isError: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester,
    contentDescription: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = code,
        onValueChange = { onCodeChange(AuthInput.sanitizeCode(it)) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .semantics {
                // Lets the keyboard offer the code straight from the email notification.
                contentType = ContentType.SmsOtpCode
                this.contentDescription = contentDescription
            },
        decorationBox = { _ ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                repeat(AuthInput.CODE_LENGTH) { index ->
                    CodeBox(
                        digit = code.getOrNull(index),
                        // The box the next digit will land in is highlighted, so the
                        // "cursor" is visible without drawing a real one.
                        active = enabled && index == code.length.coerceAtMost(AuthInput.CODE_LENGTH - 1),
                        isError = isError,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}

@Composable
private fun CodeBox(
    digit: Char?,
    active: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    val borderColor: Color = when {
        isError -> MarkerColors.Broken
        active -> KantaTheme.colors.brand
        else -> KantaTheme.colors.outline
    }
    Box(
        modifier = modifier
            .height(56.dp)
            .background(KantaTheme.colors.surfaceMuted, KantaShape.chip)
            .border(BorderStroke(if (active || isError) 2.dp else Spacing.hairline, borderColor), KantaShape.chip),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = digit?.toString() ?: "",
            style = MaterialTheme.typography.titleLarge.tabularFigures(),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
