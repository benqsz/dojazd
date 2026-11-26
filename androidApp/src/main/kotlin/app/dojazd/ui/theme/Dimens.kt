package app.dojazd.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The steps of space between and around things. Every padding and gap in the
 * app is one of these.
 */
object Spacing {
    /** Between a text and what belongs to it most closely: a line under a title, a nudge to align an icon. */
    val tiny = 2.dp

    /** Between an icon and the badge or number it goes with; around the text of a compact control. */
    val extraSmall = 4.dp

    /** Between an icon and its text, between chips, between the lines of a block. */
    val small = 8.dp

    /** Between the parts of a card or a dialog. */
    val medium = 12.dp

    /** From the edges of the screen; between the blocks of a screen. */
    val large = 16.dp

    /** Around a message that stands alone on the screen. */
    val extraLarge = 24.dp

    /** Around the content of a screen that holds little, such as the first-run screens. */
    val huge = 32.dp
}

/** The sizes icons are drawn at. */
object IconSize {
    /** Inside a line of small text: the live mark, the two halves of a stop served by two kinds of vehicle. */
    val tiny = 16.dp

    /** Beside a line of text, and in buttons and chips. */
    val small = 18.dp

    /** On its own; Material's default. */
    val medium = 24.dp
}

/** The sizes of controls that are not Material's own. */
object ControlSize {
    /** The least a thing that can be tapped measures either way. */
    val touchTarget = 48.dp

    /** The height of a button that should not draw the eye from the content around it. */
    val compactButton = 36.dp
}
