package app.dojazd.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.dojazd.R
import app.dojazd.data.TimetableRepository
import app.dojazd.gtfs.Timetable
import app.dojazd.gtfs.toGtfsDate
import app.dojazd.model.Line
import app.dojazd.provider.CityConfig
import app.dojazd.ui.screens.rememberToday
import app.dojazd.ui.theme.IconSize
import app.dojazd.ui.theme.Spacing
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The line number on the operator's own colours, falling back to the theme when the feed has none. */
@Composable
fun LineBadge(
    line: Line,
    modifier: Modifier = Modifier,
    /** Smaller, to sit level with an icon in a row of them. */
    compact: Boolean = false,
) {
    val (background, content) = lineColors(line)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = background,
        contentColor = content,
        border = lineBorder(background),
    ) {
        Text(
            text = line.name,
            modifier =
                if (compact) {
                    Modifier
                        .defaultMinSize(minWidth = 32.dp)
                        .padding(horizontal = Spacing.extraSmall, vertical = Spacing.tiny)
                } else {
                    Modifier
                        .defaultMinSize(minWidth = 44.dp)
                        .padding(horizontal = Spacing.small, vertical = Spacing.extraSmall)
                },
            style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** The colour of a filled shape and of what is written or drawn on it. */
data class Colors(
    val background: Color,
    val content: Color,
) {
    /** The same pair as ARGB numbers, for drawing outside Compose such as on the map. */
    fun toArgb() = ArgbColors(background.toArgb(), content.toArgb())
}

/** [Colors] as ARGB numbers. */
data class ArgbColors(
    val background: Int,
    val content: Int,
)

/** Background and text colour for a line: the operator's pair when the feed has both, else the theme's. */
@Composable
fun lineColors(line: Line): Colors {
    val background = line.color
    val text = line.textColor
    return if (background != null && text != null) {
        Colors(opaque(background), opaque(text))
    } else {
        Colors(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/**
 * An outline for a line colour that would blend into the screen behind it, such
 * as the black of night lines on a dark theme; null when the colour stands out.
 */
@Composable
fun lineBorder(background: Color): BorderStroke? {
    val surface = MaterialTheme.colorScheme.surface.luminance()
    val line = background.luminance()
    val contrast = (maxOf(surface, line) + 0.05f) / (minOf(surface, line) + 0.05f)
    return if (contrast < MIN_LINE_CONTRAST) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
}

/** [lineColors] for drawing outside Compose, such as on the map. */
@Composable
fun rememberLineColorsArgb(): (Line) -> ArgbColors {
    val fallback = Colors(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer).toArgb()
    return remember(fallback) {
        { line ->
            val background = line.color
            val text = line.textColor
            if (background != null && text != null) Colors(opaque(background), opaque(text)).toArgb() else fallback
        }
    }
}

/** WCAG contrast ratio below which a filled shape is hard to tell from its surroundings. */
private const val MIN_LINE_CONTRAST = 1.5f

private fun opaque(rgb: Int) = Color(0xFF000000.toInt() or rgb)

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
    }
}

/**
 * A top bar whose [content] takes as many lines as it needs, where the standard
 * bar would cut a long name short. Any [actions] stand at its end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WrappingTopBar(
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                    .heightIn(min = TOP_BAR_HEIGHT)
                    .padding(horizontal = Spacing.extraSmall, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack)
            Box(Modifier.weight(1f).padding(start = Spacing.extraSmall, end = Spacing.medium)) { content() }
            actions()
        }
    }
}

/** The height of Material's small top bar, which [WrappingTopBar] keeps to while its content fits on one line. */
private val TOP_BAR_HEIGHT = 64.dp

/**
 * A dialog over the whole screen: a bar with [title] and a close button, and
 * [content] under it. [onClose] is what the close button does, when that is
 * not the same as leaving the dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenDialog(
    title: @Composable () -> Unit,
    onDismiss: () -> Unit,
    onClose: () -> Unit = onDismiss,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                TopAppBar(
                    title = title,
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
                        }
                    },
                    // The dialog window is already laid out inside the system bars.
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
                content()
            }
        }
    }
}

/** The title over a group of things in a list or a form, with an [icon] before it when given. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, Modifier.size(IconSize.small), tint = MaterialTheme.colorScheme.primary)
        }
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

/** The room around a [SectionHeader] that stands in a list reaching the edges of the screen. */
fun Modifier.sectionHeaderPadding(): Modifier =
    padding(start = Spacing.large, end = Spacing.large, top = Spacing.large, bottom = Spacing.extraSmall)

/** How a link inside a text is told from the words around it. */
@Composable
fun linkStyles(): TextLinkStyles =
    TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))

/** [text] as a link that opens [url] in the browser. */
@Composable
fun LinkText(
    text: String,
    url: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    val styles = linkStyles()
    Text(
        buildAnnotatedString { withLink(LinkAnnotation.Url(url, styles)) { append(text) } },
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = textAlign,
    )
}

/**
 * The version of the open timetable, for starting work over when it is replaced.
 * Read this way a screen is drawn again only then, not at every step of a
 * download or an import going on behind it.
 */
@Composable
fun rememberTimetableVersion(repository: TimetableRepository): Int {
    val status = repository.status.collectAsState()
    val version by remember(status) { derivedStateOf { status.value.version } }
    return version
}

/**
 * Says that the timetable has run out, when it has: its last day of service is
 * behind in [city]. Null while it still holds, which is nearly always. An empty
 * board is otherwise no different from a night without departures.
 */
@Composable
fun expiredTimetableNote(
    repository: TimetableRepository,
    city: CityConfig,
): String? {
    val status = repository.status.collectAsState()
    val validUntil by remember(status) { derivedStateOf { status.value.validUntil } }
    val last = validUntil ?: return null
    if (rememberToday(city.timezone).toGtfsDate() <= last) return null
    val day = LocalDate(last / 10_000, last / 100 % 100, last % 100)
    return stringResource(R.string.timetable_expired, day.toJavaLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
}

/** Tells the user something in passing, in a message that goes away on its own. */
fun Context.showMessage(
    text: String,
    long: Boolean = false,
) = Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

/** A single-choice row of connected buttons. */
@Composable
fun <T> Choices(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                // The default check mark leaves too little room for four labels.
                icon = {},
            ) {
                Text(label, maxLines = 1)
            }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun MessageBox(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(Spacing.extraLarge), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

/**
 * Like [rememberTimetableQuery] for something the same on every screen: worked
 * out once per timetable under [key] and kept, instead of again for each screen.
 */
@Composable
fun <T> rememberTimetableMemo(
    repository: TimetableRepository,
    key: String,
    block: (Timetable) -> T,
): State<T?> {
    val version =
        rememberTimetableVersion(repository)
    return produceState<T?>(initialValue = null, version, key) {
        value = repository.remembered(key, block)
    }
}

/**
 * Runs [block] against the open timetable off the main thread and re-runs it
 * when [keys] change or the timetable is replaced. The value is null while loading.
 */
@Composable
fun <T> rememberTimetableQuery(
    repository: TimetableRepository,
    vararg keys: Any?,
    block: (Timetable) -> T,
): State<T?> {
    val version =
        rememberTimetableVersion(repository)
    return produceState<T?>(initialValue = null, version, *keys) {
        value = repository.query(block)
    }
}

/**
 * "From → to" for a journey. The arrow is drawn as an icon centred on the
 * letters, because the arrow character of the system font sits near the baseline.
 */
@Composable
fun RouteText(
    from: String,
    to: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = 1,
    /** The icon of the favourite place [from] is, drawn before it; null for none. */
    fromIcon: Int? = null,
    /** The same for [to]. */
    toIcon: Int? = null,
) {
    // Inline content needs text for screen readers, and it must not be empty.
    val favourite = stringResource(R.string.favourite_place)
    val text =
        buildAnnotatedString {
            if (fromIcon != null) {
                appendInlineContent(FROM_ICON, favourite)
                append(' ')
            }
            append(from)
            append(' ')
            // Read out as an arrow by screen readers, as the character was.
            appendInlineContent(ROUTE_ARROW, "→")
            append(' ')
            if (toIcon != null) {
                appendInlineContent(TO_ICON, favourite)
                append(' ')
            }
            append(to)
        }

    fun icon(drawable: Int) =
        InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
            Icon(painterResource(drawable), contentDescription = null, Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.primary)
        }
    Text(
        text = text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        inlineContent =
            buildMap {
                put(
                    ROUTE_ARROW,
                    InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                        Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = null, Modifier.fillMaxSize())
                    },
                )
                fromIcon?.let { put(FROM_ICON, icon(it)) }
                toIcon?.let { put(TO_ICON, icon(it)) }
            },
    )
}

private const val ROUTE_ARROW = "arrow"
private const val FROM_ICON = "from-icon"
private const val TO_ICON = "to-icon"
