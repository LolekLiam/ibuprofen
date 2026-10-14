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
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialTab = AppTab.entries.firstOrNull { it.name == intent.getStringExtra("open_tab") }
        setContent {
            IbuprofenTheme {
                TimetableScreen(initialTab = initialTab, jumpToChildDate = intent.getStringExtra("jump_to_child_date"))
            }
        }
    }
}

@Composable
fun TimetableScreen(modifier: Modifier = Modifier, initialTab: AppTab? = null, jumpToChildDate: String? = null) {
    val context = LocalContext.current
    val tokens = remember { TokenStore(context.applicationContext) }
    val settings = remember { SettingsStore(context.applicationContext) }
    val auth = remember { AuthRepository(NetworkProvider.authApi, tokens) }
    var session by remember { mutableStateOf(auth.currentSession()) }
    var tab by rememberSaveable { mutableStateOf(initialTab ?: AppTab.Public) }
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
        onSelect = { persistChild(it) }, onRetry = { profilesRevision++ }, onSignIn = { tab = AppTab.Login })
    val tabs = listOf(AppTab.Public, AppTab.Child, AppTab.Grades, AppTab.Login, AppTab.Settings)
    Scaffold(modifier.fillMaxSize(), bottomBar = {
        NavigationBar {
            tabs.forEach { destination ->
                NavigationBarItem(selected = tab == destination, onClick = { tab = destination },
                    icon = { NavigationIcon(destination) }, label = { Text(when (destination) {
                        AppTab.Public -> "Timetable"
                        AppTab.Child -> "My schedule"
                        AppTab.Grades -> "Grades"
                        AppTab.Login -> "Account"
                        AppTab.Settings -> "Settings"
                    }, maxLines = 1, style = MaterialTheme.typography.labelSmall) })
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 12.dp)) {
            viewState.SaveableStateProvider(tab.name) {
                when (tab) {
                    AppTab.Public -> PublicTimetableScreen(school, schoolLoading, schoolError, { schoolRevision++ }, settings)
                    AppTab.Login -> AccountScreen(auth, session, profiles, profilesLoading, profilesError,
                        onSignedIn = {
                            disableReminders(); settings.clearChild(); selectedUuid = null
                            profiles = emptyList(); session = it; profilesRevision++
                        }, onSignedOut = { sessionExpired() }, onOpenSchedule = { tab = AppTab.Child },
                        onChooseProfile = { persistChild(it); tab = AppTab.Child })
                    AppTab.Child -> PersonalTimetableScreen(auth, profileUi, jumpToChildDate, onSessionExpired = { sessionExpired() })
                    AppTab.Grades -> GradesScreen(auth, profileUi, onSessionExpired = { sessionExpired() })
                    AppTab.Settings -> SettingsScreen(reminders, remindersError, child, proxyEnabled, proxyUrl,
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
