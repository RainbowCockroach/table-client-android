package com.rainbowcockroach.table.tableandroidclient.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Whether Android will let a queued transfer run outside a Doze maintenance window.
 *
 * DESIGN §6: WorkManager's queue is deferrable, so Doze and App Standby can hold a queued
 * transfer for as long as the device stays idle. Re-read on resume, because the user grants
 * the exemption on a system screen and comes back to this one.
 */
@Composable
fun rememberBatteryExemption(): Boolean {
    val context = LocalContext.current
    var exempt by remember(context) { mutableStateOf(context.isBatteryExempt()) }
    LifecycleResumeEffect(context) {
        exempt = context.isBatteryExempt()
        onPauseOrDispose {}
    }
    return exempt
}

/**
 * Opens the system list of battery-optimized apps.
 *
 * Requesting the exemption in one tap needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which is
 * more than a setting the user changes once is worth — the list screen needs no permission.
 */
fun Context.openBatteryOptimizationSettings() {
    try {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        // Some ROMs ship no such screen; the app's own page always has a Battery entry.
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
        )
    }
}

private fun Context.isBatteryExempt(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
