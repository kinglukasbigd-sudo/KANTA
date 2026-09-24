package mk.kanta.app.core.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import mk.kanta.app.R
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.roundToInt

/**
 * "31 h", "3 d", "<1 h" — how long something has been waiting (§4.1, §5.4).
 * Deliberately coarse: the point is how long a problem was ignored, not a
 * stopwatch. Days from 48 h on, so "31 h" stays the more telling "31 h".
 */
@Composable
fun compactDuration(hours: Double): String = when {
    hours < 1.0 -> stringResource(R.string.duration_under_hour)
    hours < 48.0 -> stringResource(R.string.duration_hours, hours.roundToInt())
    else -> stringResource(R.string.duration_days, (hours / 24).roundToInt())
}

/** Epoch millis of an ISO-8601 timestamp from the server, or null if it is not one. */
fun isoToMillis(iso: String?): Long? =
    iso?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/** Hours between two instants, in millis. */
fun hoursBetween(fromMillis: Long, toMillis: Long): Double =
    Duration.between(Instant.ofEpochMilli(fromMillis), Instant.ofEpochMilli(toMillis)).toMinutes() / 60.0
