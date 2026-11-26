package app.dojazd.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import app.dojazd.R
import app.dojazd.data.TimetableRepository
import app.dojazd.model.GeoPoint
import app.dojazd.model.SearchText
import app.dojazd.model.VehicleType
import app.dojazd.ui.theme.IconSize

/** All boarding points sharing one name, which is how passengers think of "a stop". */
class StopGroup(
    val name: String,
    val searchKey: String,
    val stopIds: List<Long>,
    val center: GeoPoint,
    /** The kinds of vehicle that call at any of its boarding points. */
    val types: Set<VehicleType>,
)

/** Every stop of the open timetable by name, in alphabetical order; null while loading. */
@Composable
fun rememberStopGroups(repository: TimetableRepository): State<List<StopGroup>?> =
    rememberTimetableMemo(repository, "stop-groups") { timetable ->
        val typesByStop = timetable.vehicleTypesByStop()
        timetable
            .boardingStops()
            .groupBy { it.name }
            .map { (name, stops) ->
                StopGroup(
                    name = name,
                    searchKey = SearchText.fold(name),
                    stopIds = stops.map { it.id },
                    center = GeoPoint(stops.map { it.location.lat }.average(), stops.map { it.location.lon }.average()),
                    types = stops.flatMapTo(HashSet()) { typesByStop[it.id].orEmpty() },
                )
            }.sortedBy { it.searchKey }
    }

/**
 * A stop shown by what calls at it: a bus, a tram, or both drawn together
 * for a stop served by both. A stop whose vehicles are not known gets a signpost.
 */
@Composable
fun StopIcon(
    types: Set<VehicleType>,
    modifier: Modifier = Modifier,
) {
    // Trams first: of a mixed stop, the tracks are what is harder to miss.
    val shown = types.sortedBy { it.ordinal }.take(2)
    when (shown.size) {
        0 -> {
            Icon(painterResource(R.drawable.ic_signpost), contentDescription = null, modifier = modifier)
        }

        1 -> {
            Icon(painterResource(vehicleIcon(shown[0])), contentDescription = null, modifier = modifier)
        }

        else -> {
            Box(modifier.size(IconSize.medium)) {
                Icon(
                    painterResource(vehicleIcon(shown[0])),
                    contentDescription = null,
                    modifier = Modifier.size(IconSize.tiny).align(Alignment.TopStart),
                )
                Icon(
                    painterResource(vehicleIcon(shown[1])),
                    contentDescription = null,
                    modifier = Modifier.size(IconSize.tiny).align(Alignment.BottomEnd),
                )
            }
        }
    }
}

/** The icon of one kind of vehicle. */
fun vehicleIcon(type: VehicleType): Int =
    when (type) {
        VehicleType.TRAM -> R.drawable.ic_tram
        VehicleType.RAIL -> R.drawable.ic_train
        VehicleType.METRO -> R.drawable.ic_subway
        VehicleType.FERRY -> R.drawable.ic_directions_boat
        VehicleType.BUS, VehicleType.OTHER -> R.drawable.ic_directions_bus
    }
