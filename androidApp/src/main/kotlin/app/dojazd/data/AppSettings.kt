package app.dojazd.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class ThemeSettings(
    /** Light or dark, or [ThemeMode.SYSTEM] to follow the device. */
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** ARGB seed colour chosen by the user, or null to follow the system (wallpaper) colours. */
    val seedColor: Int? = null,
    /** Maximum contrast, chosen in the app only; off by default, and Android's own contrast settings are not followed. */
    val highContrast: Boolean = false,
)

data class DataSettings(
    /**
     * How often to look for a newer timetable, in minutes. [NEVER] means the app
     * does not contact the network unless the user asks it to.
     */
    val refreshMinutes: Int = DAILY,
    /** Delays, vehicle positions and service alerts, fetched while a screen that shows them is open. */
    val live: Boolean = true,
    /**
     * Nothing is fetched from the network at all: the user imports the timetable
     * from a file, and live data is not shown.
     */
    val offline: Boolean = false,
) {
    val automatic: Boolean get() = refreshMinutes != NEVER && !offline

    companion object {
        const val NEVER = 0
        const val QUARTER_HOUR = 15
        const val DAILY = 24 * 60
        const val WEEKLY = 7 * 24 * 60
    }
}

data class PrivacySettings(
    /** Recent journeys and recent searches are remembered; switching it off also forgets those kept so far. */
    val history: Boolean = true,
    /**
     * The keyboard may learn from what is typed into the app's search fields.
     * Off unless the user allows it: addresses typed there say where the user lives and goes.
     */
    val keyboardLearning: Boolean = false,
)

/** Small user preferences, kept in SharedPreferences on the device. */
class AppSettings(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _cityId = MutableStateFlow(prefs.getString(KEY_CITY, null))
    val cityId: StateFlow<String?> = _cityId.asStateFlow()

    private val _theme =
        MutableStateFlow(
            ThemeSettings(
                mode =
                    prefs
                        .getString(KEY_THEME_MODE, null)
                        ?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } }
                        ?: ThemeMode.SYSTEM,
                // The red and pink themes are no longer offered; whoever had chosen one is back on the default colours.
                seedColor = if (prefs.contains(KEY_SEED_COLOR)) prefs.getInt(KEY_SEED_COLOR, 0).takeIf { it !in REMOVED_SEEDS } else null,
                highContrast = prefs.getBoolean(KEY_HIGH_CONTRAST, false),
            ),
        )
    val theme: StateFlow<ThemeSettings> = _theme.asStateFlow()

    private val _data =
        MutableStateFlow(
            DataSettings(
                refreshMinutes = prefs.getInt(KEY_REFRESH_MINUTES, DataSettings.DAILY),
                live = prefs.getBoolean(KEY_LIVE, true),
                offline = prefs.getBoolean(KEY_OFFLINE, false),
            ),
        )
    val data: StateFlow<DataSettings> = _data.asStateFlow()

    private val _privacy =
        MutableStateFlow(
            PrivacySettings(
                history = prefs.getBoolean(KEY_HISTORY, true),
                keyboardLearning = prefs.getBoolean(KEY_KEYBOARD_LEARNING, false),
            ),
        )
    val privacy: StateFlow<PrivacySettings> = _privacy.asStateFlow()

    private val _language = MutableStateFlow(prefs.getString(KEY_LANGUAGE, null))

    /** BCP 47 tag of the language chosen in the app, or null to follow the system. */
    val language: StateFlow<String?> = _language.asStateFlow()

    fun selectCity(id: String?) {
        prefs.edit { if (id == null) remove(KEY_CITY) else putString(KEY_CITY, id) }
        _cityId.value = id
    }

    fun setTheme(theme: ThemeSettings) {
        prefs.edit {
            if (theme.seedColor == null) remove(KEY_SEED_COLOR) else putInt(KEY_SEED_COLOR, theme.seedColor)
            putBoolean(KEY_HIGH_CONTRAST, theme.highContrast)
            putString(KEY_THEME_MODE, theme.mode.name)
        }
        _theme.value = theme
    }

    fun setData(data: DataSettings) {
        prefs
            .edit {
                putInt(KEY_REFRESH_MINUTES, data.refreshMinutes)
                putBoolean(KEY_LIVE, data.live)
                putBoolean(KEY_OFFLINE, data.offline)
            }
        _data.value = data
    }

    fun setPrivacy(privacy: PrivacySettings) {
        prefs.edit {
            putBoolean(KEY_HISTORY, privacy.history)
            putBoolean(KEY_KEYBOARD_LEARNING, privacy.keyboardLearning)
        }
        _privacy.value = privacy
    }

    /** Takes effect when the activity is recreated. */
    fun setLanguage(tag: String?) {
        prefs.edit { if (tag == null) remove(KEY_LANGUAGE) else putString(KEY_LANGUAGE, tag) }
        _language.value = tag
    }

    private companion object {
        const val KEY_CITY = "city"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_SEED_COLOR = "seed_color"

        val REMOVED_SEEDS = setOf(0xFFC62828.toInt(), 0xFFC2185B.toInt())
        const val KEY_HIGH_CONTRAST = "high_contrast"
        const val KEY_REFRESH_MINUTES = "refresh_minutes"
        const val KEY_LANGUAGE = "language"
        const val KEY_LIVE = "live"
        const val KEY_OFFLINE = "offline"
        const val KEY_HISTORY = "history"
        const val KEY_KEYBOARD_LEARNING = "keyboard_learning"
    }
}
