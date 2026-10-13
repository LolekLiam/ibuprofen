package com.ravijol1.ibuprofen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.gson.JsonParser
import com.ravijol1.ibuprofen.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.Response

private class AdminSessionExpired : IllegalStateException("Admin session expired. Sign in again.")
private fun <T> adminData(response: Response<ApiEnvelope<T>>): T {
    if (!response.isSuccessful) {
        val error = runCatching { JsonParser.parseString(response.errorBody()?.string()).asJsonObject["error"].asJsonObject }.getOrNull()
        if (response.code() == 401 && error?.get("code")?.asString != "invalid_credentials") throw AdminSessionExpired()
        throw IllegalStateException(error?.get("message")?.asString ?: "Server request failed (HTTP ${response.code()}).")
    }
    return response.body()?.data ?: error("The server returned an empty response.")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdminServerPanel(serverInput: String, onConfigure: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val server = remember(serverInput) { runCatching { NetworkProvider.adminServerUrl(serverInput) }.getOrNull() }
    val api = remember(server) { server?.let { NetworkProvider.getAdminApi(it) } }
    var token by remember(server) { mutableStateOf(server?.let { settings.adminToken(it) }) }
    var user by remember(server) { mutableStateOf<AdminUser?>(null) }
    var schools by remember(server) { mutableStateOf<List<AdminSchool>>(emptyList()) }
    var selectedSchool by remember(server) { mutableStateOf<String?>(null) }
    var jobs by remember(server) { mutableStateOf<List<SyncJob>>(emptyList()) }
    var stats by remember(server) { mutableStateOf<AdminStats?>(null) }
    var error by remember(server) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var newSchoolKey by remember { mutableStateOf(TimetableRepository.DEFAULT_SCHOOL_KEY) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    fun clearSession() {
        server?.let { settings.clearAdminSession(it) }
        token = null; user = null; schools = emptyList(); jobs = emptyList(); stats = null
    }
    suspend fun loadDashboard() {
        val client = api ?: return
        val bearer = "Bearer ${token ?: return}"
        val verified = adminData(client.me(bearer))
        check(verified.role == "admin") { "This account does not have admin access." }
        user = verified
        stats = adminData(client.stats(bearer))
        schools = adminData(client.schools(bearer))
        if (schools.none { it.key == selectedSchool }) selectedSchool = schools.firstOrNull()?.key
        jobs = adminData(client.jobs(bearer))
    }
    fun execute(operation: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { val initialToken = token; operation(); if (initialToken == token) loadDashboard()
            } catch (e: CancellationException) { throw e
            } catch (e: AdminSessionExpired) { clearSession(); error = e.message
            } catch (e: Exception) { error = e.message
            } finally { busy = false }
        }
    }
    LaunchedEffect(server, token, refreshVersion) {
        if (token == null || api == null) return@LaunchedEffect
        busy = true; error = null
        try { loadDashboard()
        } catch (e: CancellationException) { throw e
        } catch (e: AdminSessionExpired) { clearSession(); error = e.message
        } catch (e: Exception) { error = e.message
        } finally { busy = false }
    }
    LaunchedEffect(server, token, jobs.any { it.status == "queued" || it.status == "running" }) {
        if (token == null || api == null || jobs.none { it.status == "queued" || it.status == "running" }) return@LaunchedEffect
        while (true) {
            delay(5000)
            try { loadDashboard()
            } catch (e: CancellationException) { throw e
            } catch (e: AdminSessionExpired) { clearSession(); error = e.message; break
            } catch (e: Exception) { error = e.message; break }
        }
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (user == null) "Server administration" else "Admin · ${user!!.username}", style = MaterialTheme.typography.titleMedium)
            when {
                serverInput.isBlank() -> {
                    Text("Connect your self-hosted server to manage its cache and synchronization.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onConfigure, Modifier.fillMaxWidth()) { Text("Configure server") }
                }
                server == null -> {
                    Text("Enter a valid HTTP or HTTPS server URL.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onConfigure, Modifier.fillMaxWidth()) { Text("Edit server connection") }
                }
                user == null -> {
                    Text("Use the admin account created on your own server.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { showLogin = true; error = null }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Admin sign in") }
                    if (token != null && !busy) TextButton(onClick = { refreshVersion++ }) { Text("Reconnect") }
                }
                else -> {
                    stats?.let { Text("${it.schools} schools · ${it.lessons} cached lessons · ${it.active_jobs} active jobs",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    OutlinedTextField(newSchoolKey, { newSchoolKey = it }, label = { Text("School timetable key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(enabled = !busy && newSchoolKey.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                        execute { adminData(api!!.addSchool("Bearer $token", AddSchoolRequest(newSchoolKey.trim()))) }
                    }) { Text("Register school") }
                    if (schools.isNotEmpty()) {
                        var expanded by remember { mutableStateOf(false) }
                        OutlinedButton(onClick = { expanded = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(schools.firstOrNull { it.key == selectedSchool }?.name ?: "Choose a school", maxLines = 2)
                        }
                        DropdownMenu(expanded, { expanded = false }) {
                            schools.forEach { school -> DropdownMenuItem(text = { Text(school.name ?: school.key) }, onClick = { selectedSchool = school.key; expanded = false }) }
                        }
                        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                            execute { adminData(api!!.sync("Bearer $token", selectedSchool!!, SyncJobRequest("week"))) }
                        }) { Text("Sync current week") }
                        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                            execute { adminData(api!!.sync("Bearer $token", selectedSchool!!, SyncJobRequest("year"))) }
                        }) { Text("Sync school year") }
                    }
                    if (jobs.isNotEmpty()) Text("Recent synchronization", style = MaterialTheme.typography.labelLarge)
                    jobs.take(5).forEach { job ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${if (job.scope == "year") "School year" else "Week ${job.week ?: "current"}"} · ${job.status}", style = MaterialTheme.typography.bodyMedium)
                            Text("${job.completed}/${job.total} complete${if (job.failed > 0) " · ${job.failed} failed" else ""}", style = MaterialTheme.typography.bodySmall)
                            if (job.status == "running" && job.total > 0) LinearProgressIndicator(
                                progress = { (job.completed + job.failed).toFloat() / job.total }, modifier = Modifier.fillMaxWidth())
                            if (job.status == "queued") Text("Waiting for the server worker", style = MaterialTheme.typography.bodySmall)
                            job.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    TextButton(onClick = { refreshVersion++ }, enabled = !busy) { Text("Refresh admin status") }
                    OutlinedButton(onClick = {
                        scope.launch { try { api!!.logout("Bearer $token") } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                            finally { clearSession() } }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Admin sign out") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (showLogin) ModalBottomSheet(onDismissRequest = { if (!busy) { showLogin = false; password = "" } },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Admin sign in", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(username, { username = it }, label = { Text("Admin username") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Admin password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                enabled = !busy, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && username.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                execute {
                    val session = adminData(api!!.login(AdminLogin(username.trim(), password)))
                    check(session.user.role == "admin") { "Admin access required." }
                    settings.saveAdminSession(server!!, session.token)
                    token = session.token; password = ""; showLogin = false
                }
            }) { Text(if (busy) "Signing in…" else "Sign in") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(16.dp))
        }
    }
}
