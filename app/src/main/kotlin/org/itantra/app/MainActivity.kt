package org.itantra.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import org.itantra.app.ui.AppRoot
import org.itantra.app.ui.LocaleManager
import org.itantra.app.ui.OnboardingScreen
import org.itantra.app.ui.theme.ItantraTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startTalkServiceIfPermitted()
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val onboardingAlreadyDone = LocaleManager.isOnboardingDone(this)
        setContent {
            ItantraTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    var showOnboarding by remember { mutableStateOf(!onboardingAlreadyDone) }
                    if (showOnboarding) {
                        OnboardingScreen(
                            onFinished = { lang ->
                                LocaleManager.setOnboardingDone(this@MainActivity, true)
                                // Recreate so the newly-chosen UI locale takes effect everywhere
                                // (attachBaseContext re-runs on the fresh instance); the new
                                // onCreate() sees onboardingAlreadyDone = true and requests
                                // permissions itself, so this activity doesn't need to.
                                LocaleManager.setLocaleTag(this@MainActivity, lang.code)
                                AppRepository.language.value = lang
                                recreate()
                            },
                        )
                    } else {
                        AppRoot()
                    }
                }
            }
        }
        if (onboardingAlreadyDone) requestNeededPermissions()
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
