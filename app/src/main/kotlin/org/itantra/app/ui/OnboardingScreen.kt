package org.itantra.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.itantra.app.AppRepository
import org.itantra.app.R
import org.itantra.app.ui.theme.KolamMotif
import org.itantra.app.ui.theme.Labels
import org.itantra.app.ui.theme.TricolourHairline
import org.itantra.app.ui.theme.rememberReducedMotion
import org.itantra.core.Lang

private const val PAGE_COUNT = 3

/**
 * 3-step, skippable, shown-once onboarding (see [org.itantra.app.ui.LocaleManager.isOnboardingDone]):
 * "Speak your language" (script-diversity hero + language picker) -> "Works without network" ->
 * "Built on Indian open AI" + plain-language permission explanations. [onFinished] is called once,
 * whether the user completes all 3 pages or taps Skip, passing the language chosen on step 1 (or
 * the default if the user skipped before choosing) so the caller (MainActivity) can persist it as
 * both the conversation language and the app's UI locale, then request the runtime permissions.
 */
@Composable
fun OnboardingScreen(onFinished: (Lang) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    var selectedLang by remember { mutableStateOf(AppRepository.language.value) }

    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            KolamMotif(Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize()) {
                TricolourHairline()
                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                    when (page) {
                        0 -> OnboardingStep1(selectedLang) { selectedLang = it; AppRepository.language.value = it }
                        1 -> OnboardingStep2()
                        else -> OnboardingStep3()
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { onFinished(selectedLang) }) { Text(stringResource(R.string.onboarding_skip)) }
                    PagerDots(pagerState.currentPage, PAGE_COUNT)
                    if (pagerState.currentPage < PAGE_COUNT - 1) {
                        Button(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } }) {
                            Text(stringResource(R.string.onboarding_next))
                        }
                    } else {
                        Button(onClick = { onFinished(selectedLang) }) { Text(stringResource(R.string.onboarding_get_started)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PagerDots(current: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(if (i == current) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    ),
            )
        }
    }
}

@Composable
private fun OnboardingStep1(selected: Lang, onSelect: (Lang) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(24.dp))
        CyclingGreeting()
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_step1_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            stringResource(R.string.onboarding_step1_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
        )
        LanguagePickerGrid(modifier = Modifier.weight(1f), selected = selected, onSelect = onSelect)
        Spacer(Modifier.height(8.dp))
    }
}

/** Script-diversity hero: cycles [Labels.greetings] (one native script per language, plus
 * English) with a gentle crossfade — skips the animation and just shows the first greeting when
 * [rememberReducedMotion] is on. */
@Composable
private fun CyclingGreeting() {
    val reducedMotion = rememberReducedMotion()
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        while (true) {
            delay(1800)
            index = (index + 1) % Labels.greetings.size
        }
    }
    Box(Modifier.fillMaxWidth().height(84.dp), contentAlignment = Alignment.CenterStart) {
        AnimatedContent(
            targetState = index,
            transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(500)) },
            label = "greeting",
        ) { i ->
            Text(
                Labels.greetings[i],
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun OnboardingStep2() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_step2_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            stringResource(R.string.onboarding_step2_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun OnboardingStep3() {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(24.dp))
        Icon(
            Icons.Filled.Public,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_step3_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            stringResource(R.string.onboarding_step3_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 22.dp),
        )
        Text(
            stringResource(R.string.onboarding_permissions_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))
        PermissionRow(Icons.Filled.Mic, stringResource(R.string.onboarding_permission_mic_title), stringResource(R.string.onboarding_permission_mic_body))
        Spacer(Modifier.height(10.dp))
        PermissionRow(Icons.Filled.Notifications, stringResource(R.string.onboarding_permission_notif_title), stringResource(R.string.onboarding_permission_notif_body))
    }
}

@Composable
private fun PermissionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
