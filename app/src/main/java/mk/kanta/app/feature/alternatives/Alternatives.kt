package mk.kanta.app.feature.alternatives

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mk.kanta.app.core.data.model.ContainerCategory
import mk.kanta.app.core.location.LatLon
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * The pure §5.2 rules, kept apart so each is unit tested directly.
 */
object AlternativesRules {

    /** §5.2: "max 600 m". */
    const val MAX_METRES = 600

    /** §5.2: "5–10 nearest". The server returns up to this many, nearest first. */
    const val LIMIT = 10

    /** §5.2: "highlight those ≤ 300 m". */
    const val CLOSE_METRES = 300

    /** A relaxed walk with a bag: 80 m a minute. */
    const val WALK_METRES_PER_MINUTE = 80.0

    fun isClose(metres: Int): Boolean = metres <= CLOSE_METRES

    /** Whole minutes, rounded up, never "0 min" — even next door takes a moment. */
    fun walkingMinutes(metres: Int): Int =
        ceil(metres / WALK_METRES_PER_MINUTE).toInt().coerceAtLeast(1)

    /**
     * §5.2: "small cans may substitute for nothing — only suggest big containers
     * for bags". Every suggestion is a big container, whatever the origin was.
     */
    const val KIND = "big"

    /**
     * The category to look for. A recycling container sends you to the same
     * material; anything else — a small can, a general container, "Where can I
     * throw this?" — to general waste.
     */
    fun categoryFor(origin: ContainerCategory?): ContainerCategory = origin ?: ContainerCategory.GENERAL
}

/** Where the list is measured from (§5.2). */
sealed interface AlternativesOrigin {
    val position: LatLon?

    /** A full container: after a Full report, or from its detail sheet. */
    data class Container(
        val id: String,
        val code: String,
        override val position: LatLon,
        val category: ContainerCategory,
    ) : AlternativesOrigin

    /** "Where can I throw this?" — from wherever the user is (§5.2). */
    data object MyLocation : AlternativesOrigin {
        override val position: LatLon? = null
    }
}

/**
 * Hands an origin from the report flow to the map (§4.3: "for Full: immediately
 * show Nearest containers with space"). The report screen leaves the back stack as
 * the map comes back, so the request waits here until the map picks it up.
 */
@Singleton
class AlternativesLauncher @Inject constructor() {
    private val _pending = MutableStateFlow<AlternativesOrigin?>(null)
    val pending: StateFlow<AlternativesOrigin?> = _pending.asStateFlow()

    fun request(origin: AlternativesOrigin) {
        _pending.value = origin
    }

    /** Taken by the map once; a recomposition cannot open it twice. */
    fun take(): AlternativesOrigin? = _pending.value.also { _pending.value = null }
}
