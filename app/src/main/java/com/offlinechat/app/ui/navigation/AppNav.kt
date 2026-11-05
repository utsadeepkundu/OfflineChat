package com.offlinechat.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.offlinechat.app.ui.BluetoothActions
import com.offlinechat.app.ui.chat.ChatScreen
import com.offlinechat.app.ui.groups.CreateGroupScreen
import com.offlinechat.app.ui.groups.GroupInfoScreen
import com.offlinechat.app.ui.home.HomeScreen
import com.offlinechat.app.ui.onboarding.OnboardingScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CREATE_GROUP = "create_group"
    const val CHAT = "chat/{id}"
    const val GROUP_INFO = "group_info/{id}"
    fun chat(id: Long) = "chat/$id"
    fun groupInfo(id: Long) = "group_info/$id"
}

private const val DURATION = 220

@Composable
fun AppNav(
    onboardingDone: Boolean,
    permissionsGranted: Boolean,
    actions: BluetoothActions,
    /** A conversation to open (from a notification). Call [onOpenedRequested] once it has been handled. */
    requestedConversation: Long?,
    onOpenedRequested: () -> Unit,
) {
    val nav = rememberNavController()
    val start = remember { if (onboardingDone) Routes.HOME else Routes.ONBOARDING }
    val slideIn: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.EnterTransition =
        { slideInHorizontally(tween(DURATION)) { it / 4 } + fadeIn(tween(DURATION)) }
    val slideOut: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.ExitTransition =
        { slideOutHorizontally(tween(DURATION)) { -it / 8 } + fadeOut(tween(DURATION)) }
    val popIn: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.EnterTransition =
        { slideInHorizontally(tween(DURATION)) { -it / 8 } + fadeIn(tween(DURATION)) }
    val popOut: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.ExitTransition =
        { slideOutHorizontally(tween(DURATION)) { it / 4 } + fadeOut(tween(DURATION)) }

    LaunchedEffect(requestedConversation, onboardingDone) {
        val id = requestedConversation ?: return@LaunchedEffect
        if (nav.currentDestination?.route == Routes.ONBOARDING) return@LaunchedEffect
        nav.navigate(Routes.chat(id)) {
            // Replace an already open chat instead of stacking duplicates.
            popUpTo(Routes.HOME)
            launchSingleTop = true
        }
        onOpenedRequested()
    }

    val idArg = listOf(navArgument("id") { type = NavType.LongType })

    NavHost(
        navController = nav,
        startDestination = start,
        modifier = Modifier,
        enterTransition = slideIn,
        exitTransition = slideOut,
        popEnterTransition = popIn,
        popExitTransition = popOut,
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                permissionsGranted = permissionsGranted,
                actions = actions,
                onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                permissionsGranted = permissionsGranted,
                actions = actions,
                onOpenChat = { nav.navigate(Routes.chat(it)) { launchSingleTop = true } },
                onCreateGroup = { nav.navigate(Routes.CREATE_GROUP) { launchSingleTop = true } },
            )
        }
        composable(Routes.CREATE_GROUP) {
            CreateGroupScreen(
                onBack = { nav.popBackStack() },
                onCreated = { id ->
                    nav.navigate(Routes.chat(id)) {
                        popUpTo(Routes.HOME)
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.CHAT, arguments = idArg) {
            ChatScreen(
                onBack = { nav.popBackStack() },
                onOpenGroupInfo = { nav.navigate(Routes.groupInfo(it)) { launchSingleTop = true } },
            )
        }
        composable(Routes.GROUP_INFO, arguments = idArg) {
            GroupInfoScreen(
                actions = actions,
                onBack = { nav.popBackStack() },
                onLeft = { nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
    }
}
