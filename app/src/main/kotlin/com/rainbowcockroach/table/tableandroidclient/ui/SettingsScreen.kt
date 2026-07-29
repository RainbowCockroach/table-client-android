package com.rainbowcockroach.table.tableandroidclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainbowcockroach.table.tableandroidclient.settings.TableSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val stored by viewModel.settings.collectAsStateWithLifecycle()
    val test by viewModel.connectionTest.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        // Nothing is editable until the stored values are in, or the first frame would
        // seed the fields with blanks and then overwrite what the user typed.
        val settings = stored ?: return@Scaffold
        SettingsForm(
            initial = settings,
            test = test,
            onSave = { viewModel.save(it); onBack() },
            onTest = viewModel::testConnection,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun SettingsForm(
    initial: TableSettings,
    test: ConnectionTest?,
    onSave: (TableSettings) -> Unit,
    onTest: (TableSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hostUrl by remember { mutableStateOf(initial.hostUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var allowInsecureHttp by remember { mutableStateOf(initial.allowInsecureHttp) }
    val edited = TableSettings(hostUrl, apiKey, allowInsecureHttp)

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
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Allow plain http://", style = MaterialTheme.typography.bodyLarge)
                // Conformance rule 13: refused unless the user turns this on deliberately.
                Text(
                    "Only for a server you trust on your own network.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = allowInsecureHttp, onCheckedChange = { allowInsecureHttp = it })
        }
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
