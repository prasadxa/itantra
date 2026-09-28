package org.itantra.app

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import org.itantra.app.ui.AppRoot
import org.itantra.app.ui.theme.ItantraTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startTalkServiceIfPermitted()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ItantraTheme {
                Surface(color = MaterialTheme.colorScheme.background) { AppRoot() }
            }
        }
        requestNeededPermissions()
    }

    private fun requestNeededPermissions() {
        val missing = PermissionUtil.required(android.os.Build.VERSION.SDK_INT).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startTalkServiceIfPermitted()
        } else {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startTalkServiceIfPermitted() {
        val recordAudioGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (recordAudioGranted) {
            TalkService.start(this)
        }
    }
}
