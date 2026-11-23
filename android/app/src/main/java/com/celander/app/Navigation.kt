package com.celander.app

import androidx.activity.compose.BackHandler
import com.ce.kit.ui.LocalMotion
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import dev.chrisbanes.haze.hazeSource
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.celander.app.calendar.HomeScreen
import com.celander.app.calendar.LaunchRequests
import com.ce.kit.ui.theme.CeTheme
import com.ce.kit.ui.MotionTokens
import com.celander.app.account.ui.AccountsScreen
import com.celander.app.settings.CalendarsScreen
import com.celander.app.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object SettingsRoute

@Serializable
object CalendarsRoute

@Serializable
object AccountsRoute

@Composable
fun CelanderNavHost(launchRequests: LaunchRequests) {
    val navController = rememberNavController()
    // Something opened from outside (a reminder or a widget) is shown on the home screen.
    LaunchedEffect(launchRequests) {
        launchRequests.arrivals.collect { navController.popBackStack(HomeRoute, inclusive = false) }
    }
    val motion = LocalMotion.current
    val reduced = motion.reduced
    // Pages sit side by side: forward, the new page pushes the old one out to the left; back, the page
    // slides out to the right and pulls the one below back in. Both move the full width on one eased curve.
    // One background for the whole app, under every page: pages are transparent, so it stays put while they slide
    // (and it is the blur source of every page's glass).
    val hazeState = dev.chrisbanes.haze.rememberHazeState()
    androidx.compose.runtime.CompositionLocalProvider(com.ce.kit.ui.glass.LocalHazeState provides hazeState) {
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
    com.ce.kit.ui.glass.GlassBackground(androidx.compose.ui.Modifier.hazeSource(hazeState, zIndex = com.ce.kit.ui.glass.GlassLayers.BACKGROUND)) {}
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        enterTransition = {
            if (reduced) fadeIn(tween(MotionTokens.SHORT)) else slideInHorizontally(motion.navigation()) { it }
        },
        exitTransition = {
            if (reduced) fadeOut(tween(MotionTokens.SHORT)) else slideOutHorizontally(motion.navigation()) { -it }
        },
        popEnterTransition = {
            if (reduced) fadeIn(tween(MotionTokens.SHORT)) else slideInHorizontally(motion.navigation()) { -it }
        },
        popExitTransition = {
            if (reduced) fadeOut(tween(MotionTokens.SHORT)) else slideOutHorizontally(motion.navigation()) { it }
        },
    ) {
        composable<HomeRoute> {
            HomeScreen(
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenCalendars = { navController.navigate(CalendarsRoute) },
                onOpenAccounts = { navController.navigate(AccountsRoute) },
            )
        }
        composable<SettingsRoute> {
            Page(navController) { back ->
                SettingsScreen(
                    onBack = back,
                    onOpenCalendars = { navController.navigate(CalendarsRoute) },
                    onOpenAccounts = { navController.navigate(AccountsRoute) },
                )
            }
        }
        composable<CalendarsRoute> {
            Page(navController) { back -> CalendarsScreen(onBack = back) }
        }
        composable<AccountsRoute> {
            Page(navController) { back -> AccountsScreen(onBack = back) }
        }
    }
    }
    }
}

/**
 * A page above home. The system back gesture goes the same way as the back arrow: a plain pop with
 * the full slide. Left to the predictive back handling, the gesture would drive the slide itself, and
 * some systems (ColorOS) report it as nearly finished within a tenth of a second, before the page
 * below has drawn its first frame, so no slide was visible. Only the page on top reacts, so a second
 * swipe during the slide does not pop twice.
 */
@Composable
private fun Page(navController: NavHostController, content: @Composable (back: () -> Unit) -> Unit) {
    val back = dropUnlessResumed { navController.popBackStack() }
    // Registered before the screen's own handlers, so an open dialog or sheet still closes first.
    BackHandler(onBack = back)
    content(back)
}
