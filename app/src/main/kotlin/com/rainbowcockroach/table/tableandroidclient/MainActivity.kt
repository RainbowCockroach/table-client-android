package com.rainbowcockroach.table.tableandroidclient

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.rainbowcockroach.table.tableandroidclient.ui.AppRoot
import com.rainbowcockroach.table.tableandroidclient.ui.theme.TableClientTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForTransferNotifications()
        val container = (application as TableApp).container
        setContent {
            TableClientTheme {
                AppRoot(container)
            }
        }
    }

    /**
     * A foreground transfer runs either way; without this its progress notification is
     * silently dropped on API 33+, so a backgrounded transfer shows the user nothing.
     */
    private fun askForTransferNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
