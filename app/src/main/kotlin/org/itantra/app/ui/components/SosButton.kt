package org.itantra.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.itantra.app.R

/** Prominent round red SOS button — always-visible emergency entry point, distinct from the
 * in-deck quick-reply chips. Opens [org.itantra.app.ui.sos.SosSheet]. Red is reserved for
 * ALERT/emergency per the brand rule, so this is the one place a solid-red circular control belongs. */
@Composable
fun SosButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(56.dp)
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error)
            .clickable(onClickLabel = stringResource(R.string.cd_sos_launch), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Sos, contentDescription = stringResource(R.string.cd_sos_launch), tint = Color.White, modifier = Modifier.size(28.dp))
    }
}
