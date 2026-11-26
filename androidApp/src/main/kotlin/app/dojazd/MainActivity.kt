package app.dojazd

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.dojazd.ui.DojazdRoot
import java.util.Locale

class MainActivity : ComponentActivity() {
    /** Applies the language chosen in the app's settings; without a choice the system language is used. */
    override fun attachBaseContext(newBase: Context) {
        val tag =
            (newBase.applicationContext as DojazdApplication)
                .container.settings.language.value
        val locale = tag?.let(Locale::forLanguageTag) ?: newBase.resources.configuration.locales[0]
        // Dates and numbers are formatted with the default locale, so it has to follow the choice too.
        Locale.setDefault(locale)
        // Only the language is set here; everything else follows the system as it changes.
        val configuration = Configuration().apply { setLocale(locale) }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as DojazdApplication).container
        setContent { DojazdRoot(container) }
    }
}
