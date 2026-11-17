package com.celander.app.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celander.app.R

/** A library that ships inside the app, with the license it comes under. Test-only tools are not listed. */
private data class Library(val name: String, val author: String, val url: String, @StringRes val license: Int = R.string.settings_license_apache)

private const val APACHE_2 = "https://www.apache.org/licenses/LICENSE-2.0"

private val LIBRARIES = listOf(
    Library(
        "AndroidX (Compose, Activity, Core, Lifecycle, Navigation, Room, DataStore, WorkManager, Glance)",
        "The Android Open Source Project",
        "https://developer.android.com/jetpack/androidx",
    ),
    Library("Material Components & Icons for Compose", "Google", "https://developer.android.com/jetpack/compose/designsystems/material3"),
    Library("Dagger & Hilt", "Google", "https://dagger.dev/hilt/"),
    Library("Kotlin, kotlinx.coroutines, kotlinx.serialization", "JetBrains", "https://kotlinlang.org"),
    Library("Haze", "Chris Banes", "https://github.com/chrisbanes/haze"),
    Library("OkHttp, Okio", "Square", "https://square.github.io/okhttp/"),
    Library("AppAuth for Android", "OpenID Foundation", "https://github.com/openid/AppAuth-android"),
)

/** Settings › Licenses: the libraries in the app with their licenses. */
@Composable
fun LicensesScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val snackbar by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val open = { url: String ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            viewModel.showMessage(R.string.error_no_browser)
        }
    }
    val apache = stringResource(R.string.settings_license_apache)
    val groupTitle = stringResource(R.string.settings_libraries)
    SettingsScaffold(
        title = stringResource(R.string.settings_licenses),
        onBack = onBack,
        snackbar = snackbar,
        onSnackbarAction = viewModel::onSnackbarAction,
        onSnackbarTimeout = viewModel::onSnackbarTimeout,
    ) {
        settingsGroup(groupTitle) {
            LIBRARIES.forEachIndexed { index, library ->
                if (index > 0) SettingsDivider()
                SettingsRow(title = library.name, summary = "${library.author} · ${stringResource(library.license)}", onClick = { open(library.url) })
            }
            SettingsDivider()
            SettingsRow(title = apache, onClick = { open(APACHE_2) })
        }
    }
}
