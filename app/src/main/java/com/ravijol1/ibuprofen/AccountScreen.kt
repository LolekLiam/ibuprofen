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
    profilesLoading: Boolean, profilesError: String?, onSignedIn: (AuthRepository.Session) -> Unit,
    onSignedOut: () -> Unit, onOpenSchedule: () -> Unit, onChooseProfile: (ChildProfile) -> Unit) {
    val scope = rememberCoroutineScope()
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun signIn() {
        if (busy || username.isBlank() || password.isEmpty()) return
        busy = true; error = null
        scope.launch {
            try { val connected = auth.login(username, password); password = ""; onSignedIn(connected)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { error = e.message ?: "Could not sign in. Please try again."
            } finally { busy = false }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTitle("Account", if (session == null) "Connect your eAsistent account" else "Your school account") }
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
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { signIn() }), trailingIcon = {
                                TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
                            })
                        Button(onClick = { signIn() }, enabled = !busy && username.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text(if (busy) "Signing in…" else "Sign in")
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            item { Text("Your password is used only to sign in and is never saved.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(session.userName ?: "eAsistent account", style = MaterialTheme.typography.titleLarge)
                        Text("Signed in", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onOpenSchedule, Modifier.fillMaxWidth()) { Text("Open my schedule") }
                    }
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
        error?.let { message -> item { ErrorNotice(message) } }
    }
}
