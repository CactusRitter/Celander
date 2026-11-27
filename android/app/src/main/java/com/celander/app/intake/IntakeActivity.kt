package com.celander.app.intake

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celander.app.MainActivity
import com.celander.app.calendar.EditorRequest
import com.celander.app.calendar.HomeDestination
import com.celander.app.core.time.TimeSource
import com.celander.app.core.ui.Formats
import com.celander.app.core.ui.SnackbarManager
import com.celander.app.core.ui.rememberFormats
import com.celander.app.event.EditorSheet
import com.celander.app.settings.SettingsRepository
import com.celander.app.ui.CalendarEnvironment
import com.celander.app.ui.GlassSnackbarHost
import com.ce.kit.ui.glass.GlassBackground
import com.ce.kit.ui.glass.GlassLayers
import com.ce.kit.ui.glass.LocalHazeState
import com.ce.kit.ui.theme.CeTheme
import com.ce.kit.ui.theme.isDarkNow
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.time.DayOfWeek
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.runBlocking

/**
 * Celander as "the calendar app" of other apps: Android's insert intent and opened .ics files. Its
 * own activity, so it lies over the app that asked and closing it leads back there. Nothing is
 * saved until the user presses the button of the sheet.
 */
@AndroidEntryPoint
class IntakeActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var snackbars: SnackbarManager

    @Inject lateinit var time: TimeSource

    private val viewModel: IntakeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.load(intent)
        val initialSettings = runBlocking { settingsRepository.current() }
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialSettings)
            val dark = settings.isDarkNow()
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            val reduceMotion = settings.reduceMotion ||
                Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            CeTheme(settings) {
                CalendarEnvironment(reduceMotion = reduceMotion) {
                    val hazeState = rememberHazeState()
                    CompositionLocalProvider(LocalHazeState provides hazeState) {
                        Box(Modifier.fillMaxSize()) {
                            // The only blur source: the background; the sheets lie beside it.
                            GlassBackground(Modifier.hazeSource(hazeState, zIndex = GlassLayers.BACKGROUND)) {}
                            val formats = rememberFormats(settings.timeFormat)
                            Content(formats, settings.firstDayOfWeek(formats.locale))
                            val message by snackbars.current.collectAsStateWithLifecycle()
                            GlassSnackbarHost(
                                message = message,
                                onAction = snackbars::performAction,
                                onTimeout = { snackbars.dismiss(it.id) },
                                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Content(formats: Formats, firstDay: DayOfWeek) {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val zone: ZoneId = time.zone()
        // "Edit before adding" turns the review into the editor.
        var editUid by rememberSaveable { mutableStateOf<String?>(null) }
        when (val current = state) {
            IntakeState.Loading -> Unit
            is IntakeState.Message -> MessageSheet(current.text, onDismissed = ::finish)
            is IntakeState.Edit -> Editor(current.request, formats, firstDay, zone)
            is IntakeState.Review -> {
                val edited = editUid?.let { uid -> current.items.firstOrNull { it.incoming.uid == uid } }
                if (edited != null) {
                    Editor(edited.incoming.toInsertRequest(), formats, firstDay, zone)
                } else {
                    ReviewSheet(
                        state = current,
                        formats = formats,
                        zone = zone,
                        onSelect = viewModel::select,
                        onCalendar = viewModel::setCalendar,
                        onReminders = viewModel::setReminders,
                        onConfirm = viewModel::confirm,
                        onEdit = { editUid = it.uid },
                        onShowInCalendar = { showInCalendar(it) },
                        onDismissed = ::finish,
                    )
                }
            }
        }
    }

    @Composable
    private fun Editor(request: EditorRequest.InsertEvent, formats: Formats, firstDay: DayOfWeek, zone: ZoneId) {
        EditorSheet(
            request = request,
            formats = formats,
            today = time.today(),
            firstDay = firstDay,
            zone = zone,
            onSaved = {},
            onDismissed = ::finish,
            notice = { draft ->
                val timing = draft.timing(zone)
                val conflicts by produceState(emptyList<com.celander.app.core.model.EventOccurrence>(), timing) {
                    value = runCatching { viewModel.conflicts(timing) }.getOrDefault(emptyList())
                }
                Conflicts(timing, conflicts, formats, zone)
            },
        )
    }

    private fun showInCalendar(item: IntakeItem) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_VIEW)
                .putExtra(MainActivity.EXTRA_DESTINATION, HomeDestination.DAY.name)
                .putExtra(MainActivity.EXTRA_EPOCH_DAY, item.timing.startDate(time.zone()).toEpochDay())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
