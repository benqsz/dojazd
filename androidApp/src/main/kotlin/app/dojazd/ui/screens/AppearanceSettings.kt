package app.dojazd.ui.screens

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dojazd.R
import app.dojazd.data.AppSettings
import app.dojazd.data.ThemeMode
import app.dojazd.ui.Choices
import app.dojazd.ui.SectionHeader
import app.dojazd.ui.sectionHeaderPadding
import app.dojazd.ui.theme.ControlSize
import app.dojazd.ui.theme.DefaultSeedColor
import app.dojazd.ui.theme.SeedColorChoices
import app.dojazd.ui.theme.Spacing
import app.dojazd.ui.theme.supportsSystemColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColumnScope.AppearanceSettings(settings: AppSettings) {
    val theme by settings.theme.collectAsState()
    val language by settings.language.collectAsState()
    val activity = LocalActivity.current

    SectionHeader(stringResource(R.string.settings_theme_mode), Modifier.sectionHeaderPadding())
    Choices(
        modifier = Modifier.padding(horizontal = Spacing.large),
        options =
            listOf(
                ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                ThemeMode.LIGHT to stringResource(R.string.theme_light),
                ThemeMode.DARK to stringResource(R.string.theme_dark),
            ),
        selected = theme.mode,
        onSelect = { mode -> settings.setTheme(theme.copy(mode = mode)) },
    )

    SectionHeader(stringResource(R.string.settings_color), Modifier.sectionHeaderPadding())
    // Without system colours the default seed is what "no choice" looks like.
    val selectedSeed = theme.seedColor ?: if (supportsSystemColors) null else DefaultSeedColor.toArgb()
    FlowRow(
        modifier = Modifier.padding(horizontal = Spacing.large),
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        if (supportsSystemColors) {
            ColorSwatch(
                brush = Brush.sweepGradient(SYSTEM_SWATCH_COLORS),
                checkColor = Color.White,
                selected = selectedSeed == null,
                description = stringResource(R.string.settings_color_system),
                onClick = { settings.setTheme(theme.copy(seedColor = null)) },
            )
        }
        SeedColorChoices.forEach { choice ->
            ColorSwatch(
                brush = SolidColor(choice.color),
                checkColor = Color.White,
                selected = selectedSeed == choice.color.toArgb(),
                description = stringResource(choice.nameRes),
                onClick = { settings.setTheme(theme.copy(seedColor = choice.color.toArgb())) },
            )
        }
    }

    ListItem(
        modifier =
            Modifier
                .padding(top = Spacing.small)
                .toggleable(
                    value = theme.highContrast,
                    role = Role.Switch,
                    onValueChange = { settings.setTheme(theme.copy(highContrast = it)) },
                ),
        // The row is the control; the switch itself only shows the state.
        trailingContent = { Switch(checked = theme.highContrast, onCheckedChange = null) },
    ) {
        Text(stringResource(R.string.settings_high_contrast))
    }

    SectionHeader(stringResource(R.string.settings_language), Modifier.sectionHeaderPadding())
    Choices(
        modifier = Modifier.padding(horizontal = Spacing.large),
        options =
            listOf(
                null to stringResource(R.string.language_system),
                "pl" to stringResource(R.string.language_polish),
                "en" to stringResource(R.string.language_english),
            ),
        selected = language,
        onSelect = { tag ->
            settings.setLanguage(tag)
            // Resources are bound to the activity's context, so it has to be rebuilt.
            activity?.recreate()
        },
    )
    Spacer(Modifier.height(Spacing.large))
}

@Composable
private fun ColorSwatch(
    brush: Brush,
    checkColor: Color,
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(ControlSize.touchTarget)
                .clip(CircleShape)
                .background(brush)
                // A thin rim at all times, so that a colour close to the background (black, in the dark theme) still shows.
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    CircleShape,
                ).selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = checkColor)
    }
}

private val SYSTEM_SWATCH_COLORS =
    listOf(
        Color(0xFFE53935),
        Color(0xFFFDD835),
        Color(0xFF43A047),
        Color(0xFF1E88E5),
        Color(0xFF8E24AA),
        Color(0xFFE53935),
    )
