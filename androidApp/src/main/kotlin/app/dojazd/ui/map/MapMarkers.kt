package app.dojazd.ui.map

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import app.dojazd.R
import app.dojazd.model.Line
import app.dojazd.model.VehicleType
import app.dojazd.ui.vehicleIcon
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pictures drawn on the map: line numbers on the operator's colours, the
 * two ends of a journey and their names. They are bitmaps because the map library can only put
 * images and plain text on the map, not boxes with text in them.
 * Sizes are in pixels for the screen of [resources].
 */
internal class MapMarkers(
    private val resources: Resources,
) {
    private val density = resources.displayMetrics.density

    /** [value] density-independent pixels in pixels of this screen. */
    fun dp(value: Float): Float = value * density

    /**
     * A vehicle on the move: a small round mark in its line's colours with the
     * kind of vehicle drawn in it and a ring in [ringColor] (the colour of live data).
     */
    fun vehicle(
        type: VehicleType,
        background: Int,
        content: Int,
        ringColor: Int,
    ): Bitmap {
        val radius = VEHICLE_RADIUS_DP * density
        val outer = radius + 2.5f * density
        val size = ceil(2 * outer).toInt()
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        canvas.drawCircle(center, center, outer, fill(ringColor or OPAQUE))
        canvas.drawCircle(center, center, radius, fill(background or OPAQUE))
        val icon = ResourcesCompat.getDrawable(resources, vehicleIcon(type), null)!!.mutate()
        val iconHalf = radius * 0.65f
        icon.setBounds((center - iconHalf).toInt(), (center - iconHalf).toInt(), (center + iconHalf).toInt(), (center + iconHalf).toInt())
        icon.setTint(content or OPAQUE)
        icon.draw(canvas)
        return bitmap
    }

    /**
     * A box with a bold heading "[from] → [to]", the arrow drawn as the app's
     * own icon, and [lines] under it, anchored by its bottom middle [raise]
     * pixels above the point it tells about.
     */
    fun card(
        from: String,
        to: String,
        lines: List<String>,
        background: Int,
        content: Int,
        raise: Float = 0f,
    ): Bitmap {
        val bold =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = content or OPAQUE
                textSize = LABEL_TEXT_DP * density
                typeface = Typeface.DEFAULT_BOLD
            }
        val plain =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (content and 0xFFFFFF) or DETAIL_ALPHA
                textSize = DETAIL_TEXT_DP * density
            }
        val padding = 8 * density
        val lineHeight = 17 * density
        val arrow = 14 * density
        val gap = 4 * density
        val headingWidth = bold.measureText(from) + gap + arrow + gap + bold.measureText(to)
        val width = maxOf(headingWidth, lines.maxOfOrNull(plain::measureText) ?: 0f) + 2 * padding
        val height = (lines.size + 1) * lineHeight + 2 * padding - (lineHeight - LABEL_TEXT_DP * density)
        val bitmap = createBitmap(ceil(width).toInt(), ceil(height + raise).toInt())
        val canvas = Canvas(bitmap)
        val box = RectF(0f, 0f, width, height)
        val corner = 8 * density
        canvas.drawRoundRect(box, corner, corner, fill((background and 0xFFFFFF) or LABEL_ALPHA))
        canvas.drawRoundRect(box, corner, corner, stroke(content and 0xFFFFFF or EDGE_ALPHA))
        // The heading: the arrow is centred on the letters, as the system font's arrow character is not.
        val baseline = padding - bold.ascent()
        canvas.drawText(from, padding, baseline, bold)
        val arrowLeft = padding + bold.measureText(from) + gap
        val middle = baseline + (bold.ascent() + bold.descent()) / 2
        val icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_arrow_forward, null)!!.mutate()
        icon.setBounds(arrowLeft.toInt(), (middle - arrow / 2).toInt(), (arrowLeft + arrow).toInt(), (middle + arrow / 2).toInt())
        icon.setTint(content or OPAQUE)
        icon.draw(canvas)
        canvas.drawText(to, arrowLeft + arrow + gap, baseline, bold)
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, padding, padding - plain.ascent() + (index + 1) * lineHeight, plain)
        }
        return bitmap
    }

    /**
     * The line's number in a rounded box with an edge, white unless [edgeColor]
     * says otherwise, as on the departure boards.
     */
    fun lineBadge(
        name: String,
        background: Int,
        content: Int,
        edgeColor: Int = WHITE,
        edgeDp: Float = 2f,
        heightDp: Float = 22f,
        textDp: Float = LABEL_TEXT_DP,
    ): Bitmap {
        val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = content or OPAQUE
                textSize = textDp * density
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
        val edge = edgeDp * density
        val height = heightDp * density
        val width = maxOf(height * 1.4f, text.measureText(name) + height * 0.64f)
        val bitmap = createBitmap(ceil(width + 2 * edge).toInt(), ceil(height + 2 * edge).toInt())
        val canvas = Canvas(bitmap)
        val box = RectF(edge, edge, edge + width, edge + height)
        val radius = height * 0.27f
        canvas.drawRoundRect(RectF(0f, 0f, width + 2 * edge, height + 2 * edge), radius + edge, radius + edge, fill(edgeColor or OPAQUE))
        canvas.drawRoundRect(box, radius, radius, fill(background or OPAQUE))
        val baseline = box.centerY() - (text.descent() + text.ascent()) / 2
        canvas.drawText(name, box.centerX(), baseline, text)
        return bitmap
    }

    /** Either end of a journey: a dot in a ring. */
    fun start(color: Int): Bitmap {
        val radius = 10 * density
        val size = ceil(2 * radius).toInt()
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.drawCircle(radius, radius, radius, fill(WHITE))
        canvas.drawCircle(radius, radius, radius - 2.5f * density, fill(color or OPAQUE))
        canvas.drawCircle(radius, radius, radius * 0.35f, fill(WHITE))
        return bitmap
    }

    /**
     * Where a journey ends: the app's own location pin with a white edge. Its
     * point is the middle of the bitmap's bottom edge.
     */
    fun end(color: Int): Bitmap {
        val size = PIN_SIZE * density
        val edge = 2 * density
        // The icon's point is at 22 of its 24 units; the bitmap ends there, so it can be anchored at its bottom.
        val bitmap = createBitmap(ceil(size + 2 * edge).toInt(), ceil(size * 22 / 24 + 2 * edge).toInt())
        val canvas = Canvas(bitmap)
        val pin = ResourcesCompat.getDrawable(resources, R.drawable.ic_location_on, null)!!.mutate()

        fun draw(
            dx: Float,
            dy: Float,
            tint: Int,
        ) {
            pin.setBounds((edge + dx).toInt(), (edge + dy).toInt(), (edge + dx + size).toInt(), (edge + dy + size).toInt())
            pin.setTint(tint)
            pin.draw(canvas)
        }
        // The edge: the pin in white, nudged all around.
        for (step in 0 until 8) {
            val angle = step * Math.PI / 4
            draw((cos(angle) * edge).toFloat(), (sin(angle) * edge).toFloat(), WHITE)
        }
        // The icon has a hole in its head, which should be white rather than show the map.
        canvas.drawCircle(edge + size / 2, edge + size * 9 / 24, size * 3.5f / 24, fill(WHITE))
        draw(0f, 0f, color or OPAQUE)
        return bitmap
    }

    /**
     * [text] in a small box, to be anchored by its bottom middle at the point it
     * names, so that it stands above the point whichever way the route runs.
     */
    fun label(
        text: String,
        background: Int,
        content: Int,
        /** Empty room below the box, in pixels, so that it stands that much above its point. */
        raise: Float = 0f,
        /** An icon drawn before the text, in the text's colour; null for none. */
        icon: Int? = null,
    ): Bitmap {
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = content or OPAQUE
                textSize = LABEL_TEXT_DP * density
                typeface = Typeface.DEFAULT_BOLD
            }
        val shown = if (text.length > MAX_LABEL_CHARS) text.take(MAX_LABEL_CHARS - 1).trimEnd() + "…" else text
        val padding = 6 * density
        val height = 16 * density + padding
        val iconSize = 16 * density
        val iconRoom =
            when {
                icon == null -> 0f
                shown.isEmpty() -> iconSize
                else -> iconSize + 4 * density
            }
        val width = iconRoom + paint.measureText(shown) + 2 * padding
        val bitmap = createBitmap(ceil(width).toInt(), ceil(height + raise).toInt())
        val canvas = Canvas(bitmap)
        val box = RectF(0f, 0f, width, height)
        val radius = 6 * density
        canvas.drawRoundRect(box, radius, radius, fill((background and 0xFFFFFF) or LABEL_ALPHA))
        canvas.drawRoundRect(box, radius, radius, stroke(content and 0xFFFFFF or EDGE_ALPHA))
        if (icon != null) {
            val drawable = ResourcesCompat.getDrawable(resources, icon, null)!!.mutate()
            val top = box.centerY() - iconSize / 2
            drawable.setBounds(padding.toInt(), top.toInt(), (padding + iconSize).toInt(), (top + iconSize).toInt())
            drawable.setTint(content or OPAQUE)
            drawable.draw(canvas)
        }
        val baseline = box.centerY() - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(shown, padding + iconRoom, baseline, paint)
        return bitmap
    }

    private fun fill(color: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

    private fun stroke(color: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = density
        }

    companion object {
        private const val OPAQUE = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val LABEL_ALPHA = 0xE6000000.toInt()
        private const val EDGE_ALPHA = 0x40000000
        private const val DETAIL_ALPHA = 0xC0000000.toInt()

        /** The radius of a vehicle's round mark, in density-independent pixels. */
        const val VEHICLE_RADIUS_DP = 10f
        private const val PIN_SIZE = 36
        private const val MAX_LABEL_CHARS = 32

        /** The sizes of text drawn on the map: names and line numbers, and the smaller lines of detail under a heading. */
        private const val LABEL_TEXT_DP = 13f
        private const val DETAIL_TEXT_DP = 12f

        /** The name of the badge image of [line] on a map, for [lineBadge] pictures added to a style. */
        fun badgeImage(line: Line) = "badge-${line.id}"
    }
}
