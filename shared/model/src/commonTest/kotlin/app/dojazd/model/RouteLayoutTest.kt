package app.dojazd.model

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteLayoutTest {
    private val stops = HashMap<String, Stop>()

    private fun stop(name: String) = stops.getOrPut(name) { Stop(stops.size.toLong() + 1, name, name, "", GeoPoint(0.0, 0.0)) }

    private fun route(vararg names: String) = names.map(::stop)

    /** Each row as `M` or `B` and its stop, with the rails it has: `^` above, `v` below, `<` in, `>` out, `|` main passing. */
    private fun describe(rows: List<RouteRow>): List<String> =
        rows.map { row ->
            when (row) {
                is RouteRow.Main -> {
                    "M ${row.stop.name} " +
                        (if (row.railAbove) "^" else "") +
                        (if (row.railBelow) "v" else "") +
                        (if (row.branchIn) "<" else "") +
                        (if (row.branchOut) ">" else "")
                }

                is RouteRow.Branch -> {
                    "B ${row.stop.name} " +
                        (if (row.railAbove) "^" else "") +
                        (if (row.railBelow) "v" else "") +
                        (if (row.mainPasses) "|" else "")
                }
            }
        }

    @Test
    fun listsTheUsualStopsAloneWithoutVariants() {
        // when
        val rows = RouteLayout.rows(route("A", "B", "C"), emptyList())

        // then
        assertEquals(listOf("M A v", "M B ^v", "M C ^"), describe(rows))
    }

    @Test
    fun putsADetourOnABranchThatRejoins() {
        // given: some runs go round by X and Y between B and C
        val main = route("A", "B", "C", "D")
        val detour = route("A", "B", "X", "Y", "C", "D")

        // when
        val rows = RouteLayout.rows(main, listOf(detour))

        // then
        assertEquals(
            listOf("M A v", "M B ^v>", "B X ^v|", "B Y ^v|", "M C ^v<", "M D ^"),
            describe(rows),
        )
    }

    @Test
    fun endsABranchThatGoesOnToTheDepot() {
        // given: the last runs leave the route after C for the depot
        val main = route("A", "B", "C", "D")
        val toDepot = route("A", "B", "C", "Depot")

        // when
        val rows = RouteLayout.rows(main, listOf(toDepot))

        // then: the branch leaves C and stops at the depot, with the route going on to D past it
        assertEquals(listOf("M A v", "M B ^v", "M C ^v>", "B Depot ^|", "M D ^"), describe(rows))
    }

    @Test
    fun startsABranchBeforeTheFirstUsualStop() {
        // given: the first runs come out of the depot and join the route at A
        val main = route("A", "B")
        val fromDepot = route("Depot", "A", "B")

        // when
        val rows = RouteLayout.rows(main, listOf(fromDepot))

        // then
        assertEquals(listOf("B Depot v", "M A v<", "M B ^"), describe(rows))
    }

    @Test
    fun doesNotRejoinFurtherOnThanTheNextUsualStop() {
        // given: some runs leave after A, call at X, and come back only at C, skipping B
        val main = route("A", "B", "C")
        val express = route("A", "X", "C")

        // when
        val rows = RouteLayout.rows(main, listOf(express))

        // then: drawn as leaving A and ending at X, rather than seeming to come back at B
        assertEquals(listOf("M A v>", "B X ^|", "M B ^v", "M C ^"), describe(rows))
    }

    @Test
    fun listsAStopServedByTwoVariantsOnce() {
        // given
        val main = route("A", "B")
        val first = route("A", "X", "B")
        val second = route("A", "X", "Y", "B")

        // when
        val rows = RouteLayout.rows(main, listOf(first, second))

        // then
        assertEquals(listOf("M A v>", "B X ^v|", "B Y ^v|", "M B ^<"), describe(rows))
    }
}
