package com.nuvio.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

const val TVDB_NAME = "TheTVDB"
const val TVDB_ADDRESS = "thetvdb.com"
const val TVDB_URL = "https://thetvdb.com"

val tvdbCreditShown: Boolean get() = BuildConfig.TVDB_API_KEY.isNotBlank()

/** TheTVDB's required credit: their logo, the line and their address. */
@Composable
fun TvdbCreditLine(modifier: Modifier = Modifier) {
    if (!tvdbCreditShown) return
    val style = MaterialTheme.typography.labelSmall
    val color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.85f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.tvdb_logo),
            contentDescription = TVDB_NAME,
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(14.dp)
        )
        Text(text = stringResource(R.string.tvdb_metadata_credit, TVDB_NAME), style = style, color = color, maxLines = 1)
        Text(text = "·", style = style, color = color)
        Text(text = TVDB_ADDRESS, style = style, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
    }
}
