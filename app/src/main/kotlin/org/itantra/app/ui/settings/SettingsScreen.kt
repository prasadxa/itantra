package org.itantra.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.itantra.app.AppRepository
import org.itantra.app.PermissionUtil
import org.itantra.app.ProfileManager
import org.itantra.app.ProfileOverride
import org.itantra.app.R
import org.itantra.app.TalkService
import org.itantra.app.ui.LanguagePickerGrid
import org.itantra.app.ui.LocaleManager
import org.itantra.app.ui.findActivity
import org.itantra.core.Lang
import org.itantra.core.Priority
import org.itantra.core.SynthesisPlan
import org.itantra.core.TtsSegment

/**
 * Grouped Settings screen: UI language (+conversation language, [LocaleManager] + [TalkService]),
 * performance profile ([ProfileManager], same override as [org.itantra.app.ui.ModelsScreen]),
 * speech playback prefs ([SettingsPrefs] — read by the TTS side later, see its class doc), an
 * alert test, large-text/theme display prefs, device name + battery-optimisation shortcut
 * ([PermissionUtil]), and entries into Pairing/Models/About.
 */
@Composable
fun SettingsScreen(onOpenPairing: () -> Unit, onOpenModels: () -> Unit, onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    var uiLangSheet by remember { mutableStateOf(false) }
    var convLangSheet by remember { mutableStateOf(false) }
    var deviceName by remember { mutableStateOf(SettingsPrefs.getDeviceName(context) ?: "") }
    var speechRate by remember { mutableStateOf(SettingsPrefs.getSpeechRate(context)) }
    var playbackMode by remember { mutableStateOf(SettingsPrefs.getPlaybackMode(context)) }
    var largeText by remember { mutableStateOf(SettingsPrefs.largeText(context).value) }
    var themeMode by remember { mutableStateOf(SettingsPrefs.getThemeMode(context)) }
    var profileOverride by remember { mutableStateOf(ProfileManager.getOverride(context)) }
    var alertTestResult by remember { mutableStateOf<String?>(null) }
    val language by AppRepository.language.collectAsState()
    val uiLangTag = remember { LocaleManager.getLocaleTag(context) }
    val alertSampleText = stringResource(R.string.settings_alert_test_sample)
    val alertUnavailableText = stringResource(R.string.settings_alert_test_unavailable)

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        item {
            SettingsSection(stringResource(R.string.settings_section_language)) {
                SettingsRow(
                    icon = Icons.Filled.Translate,
                    title = stringResource(R.string.settings_ui_language),
                    subtitle = uiLangTag?.let { tag -> Lang.entries.find { it.code == tag }?.label } ?: stringResource(R.string.theme_system),
                    onClick = { uiLangSheet = true },
                )
                SettingsRow(
                    icon = Icons.Filled.Translate,
                    title = stringResource(R.string.settings_conversation_language),
                    subtitle = language.label,
                    onClick = { convLangSheet = true },
                )
                ProfileOverrideRow(
                    override = profileOverride,
                    onSelect = { opt ->
                        profileOverride = opt
                        ProfileManager.setOverride(context, opt)
                        val running = TalkService.instance != null
                        TalkService.stop(context)
                        if (running) TalkService.start(context)
                    },
                )
            }
        }

        item {
            SettingsSection(stringResource(R.string.settings_section_speech)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.settings_speech_rate), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("${"%.1f".format(speechRate)}×", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                    Slider(
                        value = speechRate,
                        onValueChange = { speechRate = it; SettingsPrefs.setSpeechRate(context, it) },
                        valueRange = SettingsPrefs.SPEECH_RATE_MIN..SettingsPrefs.SPEECH_RATE_MAX,
                        steps = 4,
                    )
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(stringResource(R.string.settings_playback_mode), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = playbackMode == SettingsPrefs.PLAYBACK_SMOOTH,
                            onClick = { playbackMode = SettingsPrefs.PLAYBACK_SMOOTH; SettingsPrefs.setPlaybackMode(context, SettingsPrefs.PLAYBACK_SMOOTH) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text(stringResource(R.string.settings_playback_smooth)) }
                        SegmentedButton(
                            selected = playbackMode == SettingsPrefs.PLAYBACK_FAST,
                            onClick = { playbackMode = SettingsPrefs.PLAYBACK_FAST; SettingsPrefs.setPlaybackMode(context, SettingsPrefs.PLAYBACK_FAST) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text(stringResource(R.string.settings_playback_fast)) }
                    }
                    Text(
                        if (playbackMode == SettingsPrefs.PLAYBACK_SMOOTH) stringResource(R.string.settings_playback_smooth_desc) else stringResource(R.string.settings_playback_fast_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                SettingsRow(
                    icon = Icons.Filled.NotificationsActive,
                    title = stringResource(R.string.settings_alert_test),
                    subtitle = alertTestResult ?: stringResource(R.string.settings_alert_test_desc),
                    onClick = {
                        val output = TalkService.instance?.engines?.speechOutput
                        if (output != null) {
                            val plan = SynthesisPlan(language, Priority.ALERT, listOf(TtsSegment(alertSampleText)))
                            output.enqueue("alert-test-${System.currentTimeMillis()}", plan, phoneMode = false) { _, _ -> }
                            alertTestResult = null
                        } else {
                            alertTestResult = alertUnavailableText
                        }
                    },
                )
            }
        }

        item {
            SettingsSection(stringResource(R.string.settings_section_display)) {
                SettingsSwitchRow(
                    icon = Icons.Filled.TextFields,
                    title = stringResource(R.string.settings_large_text),
                    subtitle = stringResource(R.string.settings_large_text_desc),
                    checked = largeText,
                    onCheckedChange = { largeText = it; SettingsPrefs.setLargeText(context, it) },
                )
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val modes = SettingsPrefs.ThemeMode.entries.toList()
                        modes.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = {
                                    themeMode = mode
                                    SettingsPrefs.setThemeMode(context, mode)
                                    activity?.recreate()
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            ) {
                                Text(
                                    when (mode) {
                                        SettingsPrefs.ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                                        SettingsPrefs.ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                                        SettingsPrefs.ThemeMode.DARK -> stringResource(R.string.theme_dark)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SettingsSection(stringResource(R.string.settings_section_device)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(stringResource(R.string.settings_device_name), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.settings_device_name_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp, top = 2.dp),
                    )
                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = { deviceName = it; SettingsPrefs.setDeviceName(context, it) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(android.os.Build.MODEL ?: "iTantra") },
                    )
                }
                val batteryOk = activity?.let { PermissionUtil.isIgnoringBatteryOptimizations(it) } ?: true
                SettingsRow(
                    icon = Icons.Filled.BatteryChargingFull,
                    title = stringResource(R.string.settings_battery),
                    subtitle = if (batteryOk) stringResource(R.string.settings_battery_already) else stringResource(R.string.settings_battery_desc),
                    onClick = { activity?.let { PermissionUtil.requestIgnoreBatteryOptimizations(it) } },
                )
            }
        }

        item {
            SettingsSection(stringResource(R.string.settings_section_more)) {
                SettingsRow(Icons.Filled.QrCode, stringResource(R.string.settings_pairing), stringResource(R.string.settings_pairing_desc), onOpenPairing)
                SettingsRow(Icons.Filled.Storage, stringResource(R.string.settings_models), null, onOpenModels)
                SettingsRow(Icons.Filled.Info, stringResource(R.string.settings_about), null, onOpenAbout)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (uiLangSheet) {
        LanguageSheet(
            title = stringResource(R.string.settings_ui_language),
            selected = uiLangTag?.let { tag -> Lang.entries.find { it.code == tag } },
            onSelect = { lang ->
                uiLangSheet = false
                activity?.let { LocaleManager.applyAndRecreate(it, lang.code) }
            },
            onDismiss = { uiLangSheet = false },
        )
    }
    if (convLangSheet) {
        LanguageSheet(
            title = stringResource(R.string.settings_conversation_language),
            selected = language,
            onSelect = { lang ->
                convLangSheet = false
                TalkService.instance?.onLanguageChanged(lang)
                AppRepository.language.value = lang
            },
            onDismiss = { convLangSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSheet(title: String, selected: Lang?, onSelect: (Lang) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 14.dp))
            LanguagePickerGrid(selected = selected, onSelect = onSelect)
        }
    }
}

@Composable
private fun ProfileOverrideRow(override: ProfileOverride, onSelect: (ProfileOverride) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.settings_section_profile), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.settings_profile_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp, top = 2.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ProfileOverride.entries.forEachIndexed { i, opt ->
                SegmentedButton(
                    selected = override == opt,
                    onClick = { onSelect(opt) },
                    shape = SegmentedButtonDefaults.itemShape(i, ProfileOverride.entries.size),
                ) { Text(opt.name) }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 18.dp, bottom = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp, modifier = Modifier.padding(start = 46.dp))
}

@Composable
private fun SettingsSwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
