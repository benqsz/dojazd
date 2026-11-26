package app.dojazd.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dojazd.R
import app.dojazd.storage.Place
import app.dojazd.ui.theme.Spacing

/** An icon a saved place can be marked with. [key] is what gets stored with the place. */
class PlaceIconChoice(
    val key: String,
    @param:DrawableRes val drawable: Int,
    @param:StringRes val label: Int,
)

/** The icons offered when saving a place, the plain one first. Keys must never change: saved places refer to them. */
val PlaceIconChoices =
    listOf(
        PlaceIconChoice("", R.drawable.ic_bookmark, R.string.place_icon_plain),
        PlaceIconChoice("home", R.drawable.ic_home, R.string.place_icon_home),
        PlaceIconChoice("work", R.drawable.ic_work, R.string.place_icon_work),
        PlaceIconChoice("school", R.drawable.ic_school, R.string.place_icon_school),
        PlaceIconChoice("childcare", R.drawable.ic_child_care, R.string.place_icon_childcare),
        PlaceIconChoice("shop", R.drawable.ic_shopping_cart, R.string.place_icon_shop),
        PlaceIconChoice("restaurant", R.drawable.ic_restaurant, R.string.place_icon_restaurant),
        PlaceIconChoice("cafe", R.drawable.ic_local_cafe, R.string.place_icon_cafe),
        PlaceIconChoice("gym", R.drawable.ic_fitness_center, R.string.place_icon_gym),
        PlaceIconChoice("health", R.drawable.ic_local_hospital, R.string.place_icon_health),
        PlaceIconChoice("park", R.drawable.ic_park, R.string.place_icon_park),
        PlaceIconChoice("pets", R.drawable.ic_pets, R.string.place_icon_pets),
        PlaceIconChoice("sport", R.drawable.ic_sports_soccer, R.string.place_icon_sport),
        PlaceIconChoice("library", R.drawable.ic_local_library, R.string.place_icon_library),
        PlaceIconChoice("favourite", R.drawable.ic_favorite, R.string.place_icon_favourite),
        PlaceIconChoice("star", R.drawable.ic_star, R.string.place_icon_star),
    )

/** The icon of a saved place; the plain one when it has none or one this version of the app does not know. */
@Composable
fun PlaceIcon(
    place: Place,
    modifier: Modifier = Modifier,
) {
    Icon(painterResource(place.iconRes()), contentDescription = null, modifier)
}

/** The drawable of a saved place's icon; the plain one when it has none or one this version of the app does not know. */
fun Place.iconRes(): Int = (PlaceIconChoices.firstOrNull { it.key == icon } ?: PlaceIconChoices.first()).drawable

/**
 * The one or two lines a saved place is listed with: always its label and,
 * under it, its address, unless the address says nothing more than the label.
 */
fun Place.listing(): Pair<String, String?> = name to address.takeUnless { it.isEmpty() || it.equals(name, ignoreCase = true) }

/**
 * Asks for the label and icon of a saved place, when saving a new one or
 * editing an existing one. The [address] is what was picked on saving; it is
 * always shown; with [onChangeAddress] it can be changed, and [content] (a
 * small map) shows where it is. [onDelete] adds a delete button, for places
 * that are already saved.
 */
@Composable
fun PlaceDialog(
    title: String,
    initialName: String,
    initialIcon: String,
    address: String,
    onSave: (name: String, icon: String) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onChangeAddress: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    // A label has to fit under its icon in the grid of saved places.
    var name by rememberSaveable { mutableStateOf(labelFrom(initialName)) }
    var icon by rememberSaveable { mutableStateOf(initialIcon) }
    // Until the user types a label, a new address chosen meanwhile brings its own (see Place.hasOwnLabel).
    var typed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialName) { if (!typed) name = labelFrom(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(MAX_LABEL_LENGTH)
                        typed = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.place_label)) },
                    supportingText = { Text("${name.length} / $MAX_LABEL_LENGTH") },
                    singleLine = true,
                )
                Column {
                    PlaceIconChoices.chunked(ICONS_PER_ROW).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            row.forEach { choice ->
                                FilledIconToggleButton(checked = choice.key == icon, onCheckedChange = { icon = choice.key }) {
                                    Icon(painterResource(choice.drawable), contentDescription = stringResource(choice.label))
                                }
                            }
                        }
                    }
                }
                if (address.isNotEmpty() || onChangeAddress != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.place_address),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(address, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (onChangeAddress != null) {
                            TextButton(onClick = onChangeAddress) { Text(stringResource(R.string.place_change_address)) }
                        }
                    }
                }
                if (content != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(PREVIEW_HEIGHT)
                            .clip(MaterialTheme.shapes.medium),
                    ) { content() }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), icon) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

private const val ICONS_PER_ROW = 4
private val PREVIEW_HEIGHT = 140.dp

/** The longest label a saved place can have. */
const val MAX_LABEL_LENGTH = 12

/** [text] cut to fit as a label. */
private fun labelFrom(text: String) = text.take(MAX_LABEL_LENGTH).trimEnd()

/** False when the place is still called by (the start of) its address, as it was when saved, rather than by a label the user gave it. */
fun Place.hasOwnLabel(): Boolean = name != address && name != labelFrom(address)
