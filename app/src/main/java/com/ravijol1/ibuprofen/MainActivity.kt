package com.ravijol1.ibuprofen

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch
import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ravijol1.ibuprofen.data.*
import com.ravijol1.ibuprofen.ui.theme.IbuprofenTheme
import kotlinx.coroutines.CancellationException

enum class AppTab { Login, Public, Child, Grades, Settings }

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialTab = AppTab.entries.firstOrNull { it.name == intent.getStringExtra("open_tab") }
        setContent {
            val appearanceSettings = remember { SettingsStore(applicationContext) }
            var themeMode by remember { mutableStateOf(appearanceSettings.themeMode) }
            val darkTheme = when (themeMode) {
                "Light" -> false
                "Dark" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            IbuprofenTheme(darkTheme = darkTheme) {
                TimetableScreen(initialTab = initialTab, jumpToChildDate = intent.getStringExtra("jump_to_child_date"),
                    themeMode = themeMode, onThemeChange = { themeMode = it; appearanceSettings.themeMode = it })
            }
        }
    }
}

@Composable
fun TimetableScreen(modifier: Modifier = Modifier, initialTab: AppTab? = null, jumpToChildDate: String? = null,
    themeMode: String = "System", onThemeChange: (String) -> Unit = {}) {
    val context = LocalContext.current
    val tokens = remember { TokenStore(context.applicationContext) }
    val settings = remember { SettingsStore(context.applicationContext) }
    val updates = rememberUpdateUi(settings)
    val auth = remember { AuthRepository(NetworkProvider.authApi, tokens) }
    var session by remember { mutableStateOf(auth.currentSession()) }
    val tabs = listOf(AppTab.Public, AppTab.Child, AppTab.Grades, AppTab.Login, AppTab.Settings)
    val pager = rememberPagerState(initialPage = tabs.indexOf(initialTab ?: AppTab.Public), pageCount = { tabs.size })
    val navigationScope = rememberCoroutineScope()
    fun navigate(destination: AppTab) {
        navigationScope.launch { pager.animateScrollToPage(tabs.indexOf(destination)) }
    }
    var school by remember { mutableStateOf<SchoolMeta?>(null) }
    var schoolLoading by remember { mutableStateOf(false) }
    var schoolError by remember { mutableStateOf<String?>(null) }
    var schoolRevision by remember { mutableIntStateOf(0) }
    var profiles by remember { mutableStateOf<List<ChildProfile>>(emptyList()) }
    var profilesLoading by remember { mutableStateOf(false) }
    var profilesError by remember { mutableStateOf<String?>(null) }
    var profilesRevision by remember { mutableIntStateOf(0) }
    var selectedUuid by remember { mutableStateOf(settings.selectedChildUuid) }
    var reminders by remember { mutableStateOf(settings.remindersEnabled) }
    var remindersError by remember { mutableStateOf<String?>(null) }
    var exactAlarmsAllowed by remember { mutableStateOf(ReminderWorker.canScheduleExactAlarms(context)) }
    var notificationsAllowed by remember { mutableStateOf(NotificationHelper.canShowLessonNotifications(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentReminders by rememberUpdatedState(reminders)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exactAlarmsAllowed = ReminderWorker.canScheduleExactAlarms(context)
                notificationsAllowed = NotificationHelper.canShowLessonNotifications(context)
                if (currentReminders) ReminderWorker.enable(context.applicationContext)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun requestPreciseReminders() {
        if (Build.VERSION.SDK_INT >= 31) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.packageName}")))
    }
    var proxyEnabled by remember { mutableStateOf(settings.proxyEnabled) }
    var proxyUrl by remember { mutableStateOf(settings.proxyUrl) }
    val child = profiles.firstOrNull { it.uuid == selectedUuid }
    val viewState = rememberSaveableStateHolder()

    fun disableReminders() {
        settings.remindersEnabled = false
        reminders = false
        ReminderWorker.disable(context.applicationContext)
    }
    fun persistChild(profile: ChildProfile) {
        val changed = settings.selectedChildUuid != profile.uuid
        selectedUuid = profile.uuid
        settings.selectedChildUuid = profile.uuid
        settings.selectedChildStudentId = profile.studentId
        settings.selectedChildClassId = profile.classId
        if (changed) settings.lastReminderStartEpoch = null
        if (reminders && changed) {
            // Replace old account/profile alarms before scheduling the new selection.
            ReminderWorker.disable(context.applicationContext)
        }
    }
    fun sessionExpired() {
        tokens.clear()
        disableReminders()
        settings.clearChild()
        profiles = emptyList()
        selectedUuid = null
        session = null
    }
    fun enableReminders() {
        if (session == null || child == null) {
            remindersError = "Sign in and choose a student before enabling reminders."
            return
        }
        persistChild(child)
        remindersError = null
        settings.remindersEnabled = true
        reminders = true
        NotificationHelper.ensureChannel(context.applicationContext)
        if (!ReminderWorker.canScheduleExactAlarms(context)) requestPreciseReminders()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableReminders() else remindersError = "Allow notifications in Android settings to receive lesson reminders."
    }

    LaunchedEffect(schoolRevision, proxyEnabled, proxyUrl) {
        NetworkProvider.repository.setProxy(if (proxyEnabled) NetworkProvider.getProxyApi(proxyUrl) else null)
        NetworkProvider.repository.clearAllCaches()
        schoolLoading = true
        schoolError = null
        try { school = NetworkProvider.repository.loadSchoolMeta(TimetableRepository.DEFAULT_SCHOOL_KEY, useProxy = proxyEnabled)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { schoolError = e.message
        } finally { schoolLoading = false }
    }
    LaunchedEffect(proxyEnabled, proxyUrl) {
        NetworkProvider.repository.setProxy(if (proxyEnabled) NetworkProvider.getProxyApi(proxyUrl) else null)
    }
    LaunchedEffect(session?.userId, profilesRevision) {
        profiles = emptyList()
        profilesError = null
        if (session == null) { if (reminders) disableReminders(); return@LaunchedEffect }
        profilesLoading = true
        try {
            profiles = auth.getChildrenProfiles()
            val chosen = profiles.firstOrNull { it.uuid == selectedUuid } ?: profiles.firstOrNull()
            if (chosen != null) persistChild(chosen) else { selectedUuid = null; settings.clearChild(); disableReminders() }
            session = auth.currentSession()
        } catch (e: CancellationException) { throw e
        } catch (e: SessionExpiredException) { sessionExpired(); profilesError = e.message
        } catch (e: Exception) { profilesError = e.message
        } finally { profilesLoading = false }
    }

    LaunchedEffect(child?.uuid, reminders) {
        if (reminders && child != null && session != null) {
            NotificationHelper.ensureChannel(context.applicationContext)
            ReminderWorker.enable(context.applicationContext)
        }
    }

    val profileUi = ProfileUi(session != null, profiles, child, profilesLoading, profilesError,
        onSelect = { persistChild(it) }, onRetry = { profilesRevision++ }, onSignIn = { navigate(AppTab.Login) })
    Scaffold(modifier.fillMaxSize(), bottomBar = {
        val indicatorColor = MaterialTheme.colorScheme.secondaryContainer
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Box {
                Canvas(Modifier.matchParentSize()) {
                    val position = pager.currentPage + pager.currentPageOffsetFraction
                    val slot = size.width / tabs.size
                    val center = slot * (position + 0.5f)
                    val x = if (rtl) size.width - center else center
                    drawRoundRect(indicatorColor, topLeft = Offset(x - 32.dp.toPx(), 12.dp.toPx()),
                        size = Size(64.dp.toPx(), 32.dp.toPx()), cornerRadius = CornerRadius(16.dp.toPx()))
                }
                NavigationBar(containerColor = Color.Transparent) {
                    tabs.forEachIndexed { index, destination ->
                        NavigationBarItem(selected = pager.currentPage == index, onClick = { navigate(destination) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent),
                            icon = { NavigationIcon(destination) }, label = { Text(when (destination) {
                                AppTab.Public -> "Timetable"
                                AppTab.Child -> "Schedule"
                                AppTab.Grades -> "Grades"
                                AppTab.Login -> "Account"
                                AppTab.Settings -> "Settings"
                            }, modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE), maxLines = 1, style = MaterialTheme.typography.labelSmall) })
                    }
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppUpdateNotice(updates) { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f), key = { tabs[it].name }) { page ->
            Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                viewState.SaveableStateProvider(tabs[page].name) {
                    when (tabs[page]) {
                    AppTab.Public -> PublicTimetableScreen(school, schoolLoading, schoolError, { schoolRevision++ }, settings)
                    AppTab.Login -> AccountScreen(auth, session, profiles, profilesLoading, profilesError,
                        active = pager.settledPage == page && !pager.isScrollInProgress,
                        onSessionUpdated = { session = it },
                        onSignedIn = {
                            disableReminders(); settings.clearChild(); selectedUuid = null
                            profiles = emptyList(); session = it; profilesRevision++
                        }, onSignedOut = { sessionExpired() },
                        onChooseProfile = { persistChild(it); navigate(AppTab.Child) }, selectedChildUuid = child?.uuid)
                    AppTab.Child -> PersonalTimetableScreen(auth, profileUi, jumpToChildDate, settings, onSessionExpired = { sessionExpired() })
                    AppTab.Grades -> GradesScreen(auth, profileUi, onSessionExpired = { sessionExpired() })
                    AppTab.Settings -> SettingsScreen(reminders, remindersError, child, proxyEnabled, proxyUrl,
                        themeMode = themeMode, onThemeChange = onThemeChange, updates = updates,
                        exactAlarmsAllowed = exactAlarmsAllowed, notificationsAllowed = notificationsAllowed,
                        onAllowPreciseReminders = { requestPreciseReminders() },
                        onOpenNotificationSettings = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) },
                        onRemindersChange = { enabled ->
                            remindersError = null
                            if (!enabled) disableReminders()
                            else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else enableReminders()
                        }, onProxySave = { enabled, url ->
                            proxyEnabled = enabled; proxyUrl = url
                            settings.proxyEnabled = enabled; settings.proxyUrl = url
                        })
                }
                }
            }
        }
        }
    }
}
