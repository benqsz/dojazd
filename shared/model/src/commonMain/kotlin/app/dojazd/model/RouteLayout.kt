package app.dojazd.model

/**
 * One row of a line's route drawn as a list: a stop of the usual sequence on
 * the main rail, or a stop only some runs call at, on a branch beside it.
 */
sealed interface RouteRow {
    val stop: Stop

    /** A stop of the usual sequence. */
    data class Main(
        override val stop: Stop,
        /** The rail comes from above: this is not the first stop. */
        val railAbove: Boolean,
        /** The rail goes on below: this is not the last stop. */
        val railBelow: Boolean,
        /** A branch rejoins the route here, coming from the rows above. */
        val branchIn: Boolean,
        /** A branch leaves the route here, into the rows below. */
        val branchOut: Boolean,
    ) : RouteRow

    /** A stop only some runs call at. */
    data class Branch(
        override val stop: Stop,
        /** The branch comes from above, from the route or from another branch stop. */
        val railAbove: Boolean,
        /** The branch goes on below. */
        val railBelow: Boolean,
        /** The main rail runs past this row, from the usual stop above to the one below. */
        val mainPasses: Boolean,
    ) : RouteRow
}

object RouteLayout {
    /**
     * The usual stops of a direction, in order, with the stops only [variants]
     * call at put on branches where they leave the usual route: after the usual
     * stop they follow, or before the one they lead to when they come first.
     * A branch rejoins the route only when its variant comes back at the very
     * next usual stop; otherwise it ends where its last stop is. Each stop is listed once.
     */
    fun rows(
        main: List<Stop>,
        variants: List<List<Stop>>,
    ): List<RouteRow> {
        val mainIndex = HashMap<Long, Int>()
        main.forEachIndexed { index, stop -> mainIndex.putIfAbsent(stop.id, index) }
        val placed = HashSet<Long>(mainIndex.keys)

        // Slot k holds the branch stops between usual stops k and k + 1; slot -1 those before the first.
        val slots = HashMap<Int, Slot>()
        for (variant in variants) {
            var previous: Int? = null
            var run = ArrayList<Stop>()

            fun close(next: Int?) {
                if (run.isEmpty()) return
                val slotIndex = previous ?: next?.minus(1) ?: main.lastIndex
                val slot = slots.getOrPut(slotIndex) { Slot() }
                run.filterTo(slot.stops) { placed.add(it.id) }
                if (previous != null) slot.leaves = true
                if (next != null && next == slotIndex + 1) slot.rejoins = true
                run = ArrayList()
            }
            for (stop in variant) {
                val index = mainIndex[stop.id]
                if (index == null) {
                    run += stop
                } else {
                    close(index)
                    previous = index
                }
            }
            close(null)
        }

        val rows = ArrayList<RouteRow>()

        fun branchRows(slotIndex: Int) {
            val slot = slots[slotIndex]?.takeIf { it.stops.isNotEmpty() } ?: return
            val mainPasses = slotIndex >= 0 && slotIndex < main.lastIndex
            slot.stops.forEachIndexed { index, stop ->
                rows +=
                    RouteRow.Branch(
                        stop = stop,
                        railAbove = index > 0 || slot.leaves,
                        railBelow = index < slot.stops.lastIndex || slot.rejoins,
                        mainPasses = mainPasses,
                    )
            }
        }

        fun hasBranch(slotIndex: Int) = slots[slotIndex]?.stops?.isNotEmpty() == true

        branchRows(-1)
        main.forEachIndexed { index, stop ->
            rows +=
                RouteRow.Main(
                    stop = stop,
                    railAbove = index > 0,
                    railBelow = index < main.lastIndex,
                    branchIn = hasBranch(index - 1) && slots.getValue(index - 1).rejoins,
                    branchOut = hasBranch(index) && slots.getValue(index).leaves,
                )
            branchRows(index)
        }
        return rows
    }

    private class Slot {
        val stops = ArrayList<Stop>()

        /** Some branch here leaves the usual stop above it. */
        var leaves = false

        /** Some branch here comes back at the usual stop below it. */
        var rejoins = false
    }
}
