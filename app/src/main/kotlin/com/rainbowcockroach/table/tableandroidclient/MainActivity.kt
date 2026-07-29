package com.rainbowcockroach.table.tableandroidclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rainbowcockroach.table.tableandroidclient.ui.AppRoot
import com.rainbowcockroach.table.tableandroidclient.ui.theme.TableClientTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TableApp).container
        setContent {
            TableClientTheme {
                AppRoot(container)
            }
        }
    }
}
