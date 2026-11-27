package com.ravijol1.ibuprofen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ravijol1.ibuprofen.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AccountScreen(auth: AuthRepository, session: AuthRepository.Session?, profiles: List<ChildProfile>,
    profilesLoading: Boolean, profilesError: String?, active: Boolean,
    onSessionUpdated: (AuthRepository.Session) -> Unit, onSignedIn: (AuthRepository.Session) -> Unit,
    onSignedOut: () -> Unit, onChooseProfile: (ChildProfile) -> Unit, selectedChildUuid: String? = null) {
    var identity by remember(session) { mutableStateOf(session) }
    val identityChildUuid = selectedChildUuid ?: profiles.firstOrNull()?.uuid
    LaunchedEffect(session?.userId, identityChildUuid) {
        if (session != null && identityChildUuid != null) try { identity = auth.refreshAccountIdentity(identityChildUuid) ?: session }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Keep the saved identity when account info is unavailable. */ }
    }
    val scope = rememberCoroutineScope()
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val activity = LocalActivity.current as FragmentActivity
    val vault = remember { CredentialVault(activity) }
    var accounts by remember { mutableStateOf<List<SavedAccount>>(emptyList()) }
    var saveLogin by remember { mutableStateOf(false) }
    var editLogin by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf<SavedAccount?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var canSave by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    suspend fun reloadVault() {
        try { accounts = vault.list() }
        catch (_: Exception) { error = "Saved accounts could not be loaded. Manual sign-in is still available." }
    }
    LaunchedEffect(active) {
        if (active) { canSave = canUnlockVault(activity); reloadVault() }
        else { operation?.cancel(); password = ""; showPassword = false }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { operation?.cancel(); password = ""; showPassword = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); operation?.cancel(); password = "" }
    }
    fun signIn(saved: SavedAccount? = null) {
        if (busy || !active || (saved == null && (username.isBlank() || password.isEmpty()))) return
        busy = true; error = null
        operation = scope.launch {
            var accepted: AuthRepository.Session? = null
            var loginPassword = password
            password = ""; showPassword = false
            try {
                if (saved != null) {
                    vault.use(saved.id, { unlockVault(activity, it) }) { secret ->
                        accepted = auth.login(secret.username, secret.password, expectedUserId = saved.userId)
                    }
                } else {
                    val connected = auth.login(username, loginPassword, expectedUserId = session?.userId)
                    accepted = connected
                    if (saveLogin || session != null) {
                        try {
                            vault.save(connected, username, loginPassword) { unlockVault(activity, it) }
                            editLogin = false; saveLogin = false; reloadVault()
                        } catch (e: CancellationException) { throw e }
                        catch (_: VaultPromptCancelled) { error = "Signed in. Login was not saved." }
                        catch (_: Exception) { error = "Signed in, but login could not be saved. Your previous saved login is unchanged." }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: VaultPromptCancelled) { /* Cancellation leaves manual sign-in available. */ }
            catch (e: Exception) {
                error = if (e is VaultUnavailableException) e.message else "Could not sign in. Check your login and connection, then try again."
                if (saved != null) { username = saved.username; saveLogin = true }
            } finally {
                loginPassword = ""; password = ""; busy = false
                accepted?.let { if (session == null) onSignedIn(it) else onSessionUpdated(it) }
            }
        }
    }
    if (forget != null) AlertDialog(onDismissRequest = { forget = null },
        title = { Text("Forget saved login?") }, text = { Text("This removes the saved password from this device. Your current session stays signed in.") },
        confirmButton = { TextButton(onClick = {
            val selected = forget ?: return@TextButton
            forget = null; busy = true
            operation = scope.launch {
                try { vault.forget(selected.id); reloadVault() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "Could not remove this saved login. Try again." }
                finally { busy = false }
            }
        }) { Text("Forget") } }, dismissButton = { TextButton(onClick = { forget = null }) { Text("Cancel") } })
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTitle("Account") }
        if (session == null) {
            item {
                Card {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Welcome back", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true,
                            enabled = !busy, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                            enabled = !busy, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { signIn() }), trailingIcon = {
                                TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
                            })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = saveLogin, onCheckedChange = { saveLogin = it }, enabled = canSave && !busy)
                            Text("Save login on this device", style = MaterialTheme.typography.bodySmall)
                        }
                        if (!canSave) Text("Quick login requires strong biometrics on Android 10, or a secure screen lock on newer Android versions.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { signIn() }, enabled = !busy && username.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text(if (busy) "Signing in…" else "Sign in")
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            item { Text("Save your login encrypted on this device and unlock it with device authentication.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            item {
                Card {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(identity?.userName ?: "eAsistent account", style = MaterialTheme.typography.titleMedium)
                            identity?.email?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text("Signed in", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item {
                TextButton(onClick = { editLogin = !editLogin }, enabled = canSave && !busy) { Text(if (editLogin) "Cancel saving login" else "Save or update login") }
                if (editLogin) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Verify your current account password to save it securely.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { signIn() }))
                    Button(onClick = { signIn() }, enabled = !busy && username.isNotBlank() && password.isNotEmpty()) { Text("Verify and save login") }
                }
            }
            item { Text("Students", style = MaterialTheme.typography.titleMedium) }
            if (profilesLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            profilesError?.let { message -> item { ErrorNotice(message) } }
            items(profiles, key = { it.uuid }) { profile ->
                OutlinedCard(onClick = { onChooseProfile(profile) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(profile.displayName ?: "Student", style = MaterialTheme.typography.titleMedium)
                        Text(listOfNotNull(profile.className, profile.schoolName).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    busy = true
                    scope.launch { try { auth.logout(); onSignedOut() } finally { busy = false } }
                }) { Text(if (busy) "Signing out…" else "Sign out") }
            }
        }
        if (accounts.isNotEmpty()) {
            item { Text("Saved accounts", style = MaterialTheme.typography.titleMedium) }
            items(accounts, key = { "saved:${it.id}" }) { saved ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(saved.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(saved.username, style = MaterialTheme.typography.bodySmall)
                        }
                        if (session == null) TextButton(onClick = { signIn(saved) }, enabled = !busy && canSave) { Text("Sign in") }
                        TextButton(onClick = { forget = saved }, enabled = !busy) { Text("Forget") }
                    }
                }
            }
            if (session != null) item { Text("Sign out to use another saved account.", style = MaterialTheme.typography.bodySmall) }
        }
        error?.let { message -> item { ErrorNotice(message) } }
    }
}
