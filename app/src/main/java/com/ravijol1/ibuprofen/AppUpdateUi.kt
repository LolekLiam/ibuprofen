package com.ravijol1.ibuprofen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ravijol1.ibuprofen.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class UpdateUi {
    var enabled by mutableStateOf(true)
    var checking by mutableStateOf(false)
    var available by mutableStateOf<AppUpdate?>(null)
    var dismissed by mutableStateOf<String?>(null)
    var status by mutableStateOf("Checks GitHub once a day while the app is open.")
    var setEnabled: (Boolean) -> Unit = {}
    var checkNow: () -> Unit = {}
    var dismiss: () -> Unit = {}
}

@Composable
internal fun rememberUpdateUi(settings: SettingsStore): UpdateUi {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui = remember { UpdateUi().apply { enabled = settings.updateChecksEnabled; dismissed = settings.dismissedUpdateTag } }
    val current = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
    var job by remember { mutableStateOf<Job?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun check(force: Boolean) {
        if (!ui.enabled || ui.checking || current == null) return
        val now = System.currentTimeMillis()
        if (!force && now - settings.updateLastAttempt in 0 until 86_400_000L) return
        ui.checking = true
        job = scope.launch {
            settings.updateLastAttempt = now
            try {
                val update = AppUpdateChecker.check(current)
                ui.available = update; settings.availableUpdateTag = update?.tag
                ui.status = update?.let { "${it.tag} is available." } ?: "Up to date ($current)."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { ui.status = "Could not check GitHub. Try again later." }
            finally { ui.checking = false }
        }
    }
    ui.setEnabled = { enabled ->
        ui.enabled = enabled; settings.updateChecksEnabled = enabled
        if (!enabled) { job?.cancel(); ui.checking = false }
        else { settings.updateLastAttempt = 0; check(false) }
    }
    ui.checkNow = { check(true) }
    ui.dismiss = { ui.dismissed = ui.available?.tag; settings.dismissedUpdateTag = ui.dismissed }
    LaunchedEffect(Unit) {
        ui.available = current?.let { version -> settings.availableUpdateTag?.let { findAppUpdate(version, listOf(GithubRelease(it))) } }
        check(false)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) check(false) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel() }
    }
    return ui
}

@Composable
internal fun AppUpdateNotice(ui: UpdateUi, onOpen: (String) -> Unit) {
    val update = ui.available ?: return
    if (!ui.enabled || update.tag == ui.dismissed) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Update ${update.tag} available", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onOpen(update.url) }) { Text("View") }
            TextButton(onClick = ui.dismiss) { Text("Later") }
        }
    }
}
