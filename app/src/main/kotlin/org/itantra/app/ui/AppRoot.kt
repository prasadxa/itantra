package org.itantra.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

private enum class Tab(val label: String) { TALK("Talk"), METRICS("Metrics"), MODELS("Models") }

@Composable
fun AppRoot() {
    var tab by remember { mutableStateOf(Tab.TALK) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.TALK,
                    onClick = { tab = Tab.TALK },
                    icon = { Icon(Icons.Default.Call, contentDescription = null) },
                    label = { Text(Tab.TALK.label) },
                )
                NavigationBarItem(
                    selected = tab == Tab.METRICS,
                    onClick = { tab = Tab.METRICS },
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text(Tab.METRICS.label) },
                )
                NavigationBarItem(
                    selected = tab == Tab.MODELS,
                    onClick = { tab = Tab.MODELS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(Tab.MODELS.label) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.TALK -> TalkScreen()
                Tab.METRICS -> MetricsScreen()
                Tab.MODELS -> ModelsScreen()
            }
        }
    }
}
