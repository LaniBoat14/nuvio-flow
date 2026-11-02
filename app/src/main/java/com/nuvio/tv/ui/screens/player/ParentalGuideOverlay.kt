@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

private val ROW_HEIGHT = 18.dp
private val ROW_GAP = NuvioTheme.spacing.xxs

/**
 * Parental guide overlay showing content warnings with animated vertical line + staggered items.
 *
 * Triggered by ViewModel when video first starts playing.
 * Completely independent of player controls.
 * Auto-hides after 5 seconds with reverse animation.
 */
@Composable
fun ParentalGuideOverlay(
    warnings: List<ParentalWarning>,
    isVisible: Boolean,
    onAnimationComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (warnings.isEmpty()) return

    val count = warnings.size
    val totalLineHeight = (ROW_HEIGHT.value * count) + (ROW_GAP.value * (count - 1))
    val accentBrush = NuvioTheme.palette.accentBrush()

    var frame by remember { mutableStateOf(StaggeredOverlayFrame.hidden(count)) }
    var animating by remember { mutableStateOf(false) }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            animating = true
            runStaggeredOverlay(count) { frame = it }
            animating = false
            onAnimationComplete()
        } else if (animating) {
            frame = StaggeredOverlayFrame.hidden(count)
            animating = false
            onAnimationComplete()
        }
    }

    if (frame.containerAlpha <= 0f) return

    Row(
        modifier = modifier
            .alpha(frame.containerAlpha)
            .padding(start = NuvioTheme.spacing.xxl, top = NuvioTheme.spacing.xl),
        verticalAlignment = Alignment.Top
    ) {
        // Animated vertical line
        Box(
            modifier = Modifier
                .width(3.dp)
                .height((totalLineHeight * frame.lineFraction).dp)
                .clip(RoundedCornerShape(NuvioTheme.spacing.hairline))
                .background(accentBrush)
        )

        // Warning items
        Column(
            modifier = Modifier.padding(start = 10.dp),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP)
        ) {
            warnings.forEachIndexed { index, warning ->
                Row(
                    modifier = Modifier
                        .height(ROW_HEIGHT)
                        .alpha(frame.itemAlphas.getOrNull(index) ?: 0f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = warning.label,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = " · ",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.4f),
                    )
                    Text(
                        text = warning.severity,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}
