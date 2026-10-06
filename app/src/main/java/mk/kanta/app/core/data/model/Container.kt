package mk.kanta.app.core.data.model

/**
 * Domain enums from KANTA_SPEC.md §6 (data model) and §5.1 (status logic).
 * Kept free of any Android/UI types so both the repository layer and the marker renderer
 * can depend on them.
 */

/** Spec §6: containers.kind. */
enum class ContainerKind {
    /** Municipal or recycling container — the 1,100-litre kind. */
    BIG,

    /** Small street can. */
    SMALL,

    /**
     * §4.6 "Bin size": nobody standing next to it has said yet. Every bin starts here and gets
     * the universal marker until someone answers Small or Big.
     */
    UNKNOWN,
}

/** Spec §6: containers.category. */
enum class ContainerCategory {
    GENERAL,
    GLASS,
    PAPER,
    PLASTIC,
    MIXED_RECYCLING,
    ;

    val isRecycling: Boolean
        get() = this != GENERAL
}

/**
 * Spec §5.1: derived by the backend from open reports, never set by the client.
 * Declared in resolution-priority order so [worstOf] can just take the max.
 */
enum class ContainerStatus {
    OK,
    FULL,
    BROKEN,
    MISSING,
    DESTROYED,
    ;

    companion object {
        /**
         * Spec §5.1 priority: destroyed > missing > broken > full > ok.
         * Used for cluster colour (§3.4: "cluster colour = worst status inside").
         */
        fun worstOf(statuses: Iterable<ContainerStatus>): ContainerStatus =
            statuses.maxByOrNull { it.ordinal } ?: OK
    }
}
