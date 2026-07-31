package com.rainbowcockroach.table.tableandroidclient.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainbowcockroach.table.tableandroidclient.settings.TableSettings
import com.rainbowcockroach.table.tableandroidclient.update.RELEASES_PAGE_URL
import com.rainbowcockroach.table.tableandroidclient.update.UpdateStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val stored by viewModel.settings.collectAsStateWithLifecycle()
    val test by viewModel.connectionTest.collectAsStateWithLifecycle()
    val update by viewModel.updateCheck.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        // Nothing is editable until the stored values are in, or the first frame would
        // seed the fields with blanks and then overwrite what the user typed.
        val settings = stored ?: return@Scaffold
        SettingsForm(
            initial = settings,
            test = test,
            update = update,
            appVersion = viewModel.appVersion,
            onSave = { viewModel.save(it); onBack() },
            onTest = viewModel::testConnection,
            onCheckForUpdate = viewModel::checkForUpdate,
            onDismissUpdate = viewModel::dismissUpdate,
            onNoBrowser = viewModel::updateOpenFailed,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun SettingsForm(
    initial: TableSettings,
    test: ConnectionTest?,
    update: UpdateStatus?,
    appVersion: String,
    onSave: (TableSettings) -> Unit,
    onTest: (TableSettings) -> Unit,
    onCheckForUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onNoBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hostUrl by remember { mutableStateOf(initial.hostUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var allowInsecureHttp by remember { mutableStateOf(initial.allowInsecureHttp) }
    var uploadOnWifiOnly by remember { mutableStateOf(initial.uploadOnWifiOnly) }
    val edited = TableSettings(hostUrl, apiKey, allowInsecureHttp, uploadOnWifiOnly)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = hostUrl,
            onValueChange = { hostUrl = it },
            label = { Text("Host URL") },
            placeholder = { Text("https://files.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation =
                if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(
                        imageVector =
                            if (apiKeyVisible) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                        contentDescription = if (apiKeyVisible) "Hide API key" else "View API key",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // Conformance rule 13: refused unless the user turns this on deliberately.
        SettingSwitch(
            title = "Allow plain http://",
            explanation = "Only for a server you trust on your own network.",
            checked = allowInsecureHttp,
            onCheckedChange = { allowInsecureHttp = it },
        )
        SettingSwitch(
            title = "Upload on Wi-Fi only",
            explanation = "Queued uploads wait for an unmetered network. Downloads are unaffected.",
            checked = uploadOnWifiOnly,
            onCheckedChange = { uploadOnWifiOnly = it },
        )
        BatteryOptimizationNotice()
        UpdateRow(
            appVersion = appVersion,
            update = update,
            onCheckForUpdate = onCheckForUpdate,
            onDismissUpdate = onDismissUpdate,
            onNoBrowser = onNoBrowser,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onSave(edited) }, enabled = edited.isConfigured) { Text("Save") }
            OutlinedButton(onClick = { onTest(edited) }, enabled = edited.isConfigured) {
                Text("Test connection")
            }
        }
        test?.let { ConnectionTestResult(it) }
    }
}

@Composable
private fun UpdateRow(
    appVersion: String,
    update: UpdateStatus?,
    onCheckForUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onNoBrowser: () -> Unit,
) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Version $appVersion", style = MaterialTheme.typography.bodyLarge)
            UpdateCheckResult(update)
        }
        OutlinedButton(
            onClick = onCheckForUpdate,
            enabled = update != UpdateStatus.Checking,
        ) {
            Text("Check for update")
        }
    }
    if (update is UpdateStatus.Available) {
        UpdateAvailableDialog(
            version = update.version,
            installedVersion = appVersion,
            onDownload = { if (context.openReleasesPage()) onDismissUpdate() else onNoBrowser() },
            onDismiss = onDismissUpdate,
        )
    }
}

private fun Context.openReleasesPage(): Boolean = try {
    startActivity(Intent(Intent.ACTION_VIEW, RELEASES_PAGE_URL.toUri()))
    true
} catch (_: ActivityNotFoundException) {
    false
}

/** DESIGN §4: the download is the browser's job, and only the user's yes starts it. */
@Composable
private fun UpdateAvailableDialog(
    version: String,
    installedVersion: String,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Update available") },
    text = {
        Text(
            "Version $version is out; this build is $installedVersion. Open the releases " +
                "page to download the new APK?"
        )
    },
    confirmButton = { Button(onClick = onDownload) { Text("Download") } },
    dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Not now") } },
)

@Composable
private fun UpdateCheckResult(update: UpdateStatus?) = when (update) {
    // An available update speaks through the dialog; a second line under the version would
    // only repeat it.
    null, is UpdateStatus.Available -> Unit

    UpdateStatus.Checking -> Text("Checking…", style = MaterialTheme.typography.bodySmall)

    is UpdateStatus.UpToDate -> Text(
        "Up to date.",
        style = MaterialTheme.typography.bodySmall,
    )

    is UpdateStatus.Failed -> Text(
        "Couldn't check: ${update.message}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** Absent once the exemption is granted: there is nothing left for the user to do. */
@Composable
private fun BatteryOptimizationNotice() {
    if (rememberBatteryExemption()) return
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Transfers may be delayed", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Android holds queued transfers while the screen is off. Turn battery " +
                    "optimization off for table to have them start straight away.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(onClick = { context.openBatteryOptimizationSettings() }) { Text("Battery") }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    explanation: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) = Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(explanation, style = MaterialTheme.typography.bodySmall)
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
}

@Composable
private fun ConnectionTestResult(test: ConnectionTest) = when (test) {
    ConnectionTest.Running -> Text("Testing…", style = MaterialTheme.typography.bodyMedium)

    is ConnectionTest.Reachable -> Text(
        "Connected — ${test.fileCount} file(s) on the table.",
        style = MaterialTheme.typography.bodyMedium,
    )

    is ConnectionTest.Unreachable -> Text(
        test.message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}
