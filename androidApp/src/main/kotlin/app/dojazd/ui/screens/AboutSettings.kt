package app.dojazd.ui.screens

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.dojazd.R
import app.dojazd.provider.CityConfig
import app.dojazd.ui.LinkText
import app.dojazd.ui.SectionHeader
import app.dojazd.ui.sectionHeaderPadding
import app.dojazd.ui.theme.Spacing

/** Something made by others that ships in the app, and the license it comes under. */
private class ThirdParty(
    val name: String,
    val license: String,
    val url: String,
)

private const val APACHE = "Apache License 2.0"
private const val APACHE_URL = "https://www.apache.org/licenses/LICENSE-2.0"
private const val MIT = "MIT License"

/** Kept by hand, next to gradle/libs.versions.toml and the LICENSE files beside the data and the map. */
private val THIRD_PARTY =
    listOf(
        ThirdParty("AndroidX, Jetpack Compose", APACHE, APACHE_URL),
        ThirdParty("Kotlin, kotlinx.coroutines, kotlinx.serialization, kotlinx-datetime", APACHE, APACHE_URL),
        ThirdParty("SQLDelight", APACHE, APACHE_URL),
        ThirdParty("OkHttp, Okio, Timber, Gson", APACHE, APACHE_URL),
        ThirdParty("Material icons", APACHE, APACHE_URL),
        ThirdParty("MapLibre Native", "BSD 2-Clause License", "https://github.com/maplibre/maplibre-native/blob/main/LICENSE.md"),
        ThirdParty("Protomaps basemaps", "BSD 3-Clause License", "https://github.com/protomaps/basemaps/blob/main/LICENSE.md"),
        ThirdParty("MaterialKolor", MIT, "https://github.com/jordond/MaterialKolor/blob/main/LICENSE"),
        ThirdParty("Colormath", MIT, "https://github.com/ajalt/colormath/blob/master/LICENSE.txt"),
        ThirdParty("Noto Sans", "SIL Open Font License 1.1", "https://openfontlicense.org/open-font-license-official-text/"),
        ThirdParty("OpenStreetMap", "Open Database License 1.0", "https://www.openstreetmap.org/copyright"),
    )

private const val LICENSE_URL = "$SOURCE_URL/blob/main/LICENSE"
private const val PRIVACY_URL = "$SOURCE_URL/blob/main/PRIVACY.md"

/** Which version this is, the license it comes under, and whose work it is built on. */
@Composable
internal fun ColumnScope.AboutSettings() {
    val context = LocalContext.current
    val version =
        remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }

    Column(
        modifier = Modifier.padding(horizontal = Spacing.large, vertical = Spacing.small),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        if (version != null) Text(stringResource(R.string.about_version, version))
        Text(
            stringResource(R.string.about_license),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinkText(stringResource(R.string.about_license_link), LICENSE_URL)
        LinkText(stringResource(R.string.source_code), SOURCE_URL)
        LinkText(stringResource(R.string.about_privacy_link), PRIVACY_URL)
    }

    SectionHeader(stringResource(R.string.about_third_party), Modifier.sectionHeaderPadding())
    THIRD_PARTY.sortedBy { it.name.lowercase() }.forEach { item ->
        ListItem(supportingContent = { LinkText(item.license, item.url) }) {
            Text(item.name)
        }
    }
}
