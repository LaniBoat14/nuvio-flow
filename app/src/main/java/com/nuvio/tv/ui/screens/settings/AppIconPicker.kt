@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AppIconChoice
import com.nuvio.tv.domain.model.AppIconLook
import com.nuvio.tv.domain.model.AppIconOption
import com.nuvio.tv.domain.model.canUseAppIcon
import com.nuvio.tv.launcher.AppIconSettingsState
import com.nuvio.tv.launcher.launcherClassName
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.membership.Membership
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AppIconPickerDialog(
    state: AppIconSettingsState,
    initial: AppIconChoice,
    onSelected: (AppIconChoice) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedFocusRequester = remember { FocusRequester() }
    var look by remember { mutableStateOf(initial.look) }
    var transparent by remember { mutableStateOf(initial.transparent) }

    LaunchedEffect(state.selected) {
        selectedFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.appearance_app_icon_picker_title),
        subtitle = stringResource(
            if (state.changeFailed) {
                R.string.appearance_app_icon_change_failed
            } else {
                R.string.appearance_app_icon_picker_subtitle
            }
        ),
        width = 760.dp,
        suppressFirstKeyUp = false,
        usePlatformDefaultWidth = false,
        contentPadding = 18.dp,
        contentSpacing = 12.dp
    ) {
        val entitlements = Membership.access.entitlements
        val colours = remember(entitlements, state.selected, look, transparent) {
            AppIconOption.entries.filter { canUseAppIcon(entitlements, AppIconChoice.of(it, look, transparent), state.selected) }
        }
        val focusColour = listOf(initial.colour, state.selected.colour).firstOrNull { it in colours } ?: colours.firstOrNull()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconLook.entries.forEach { option ->
                SettingsChoiceChip(
                    label = option.localizedName(),
                    selected = look == option,
                    onClick = { look = option }
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            SettingsToggleRow(
                title = stringResource(R.string.appearance_app_icon_transparent_background),
                subtitle = stringResource(R.string.appearance_app_icon_transparent_hint),
                checked = transparent && look != AppIconLook.CLASSIC,
                onToggle = { transparent = !transparent },
                enabled = look != AppIconLook.CLASSIC,
                modifier = Modifier.weight(1f)
            )
        }
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            colours.chunked(PickerColumns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    row.forEach { colour ->
                        val choice = AppIconChoice.of(colour, look, transparent)
                        key(colour) {
                            AppIconOptionCard(
                                choice = choice,
                                selected = state.selected == choice,
                                enabled = state.pending == null,
                                onClick = { onSelected(choice) },
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (focusColour == colour) {
                                            Modifier.focusRequester(selectedFocusRequester)
                                        } else {
                                            Modifier
                                        }
                                    )
                            )
                        }
                    }
                    repeat(PickerColumns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

private const val PickerColumns = 4

@Composable
internal fun AppIconChangeConfirmationDialog(
    choice: AppIconChoice,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val confirmFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        confirmFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.appearance_app_icon_confirmation_title),
        subtitle = stringResource(
            R.string.appearance_app_icon_confirmation_message,
            choice.localizedName()
        ),
        suppressFirstKeyUp = false
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(confirmFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }
}

@Composable
private fun AppIconOptionCard(
    choice: AppIconChoice,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .alpha(if (enabled) 1f else 0.55f)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            border = if (selected) {
                Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.hairline),
                    shape = shape
                )
            } else {
                Border.None
            },
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = shape
            )
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            var widthPx by remember { mutableIntStateOf(0) }
            val thumbnail = rememberBannerThumbnail(choice, widthPx)
            val thumbnailModifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .onSizeChanged { widthPx = it.width }
                .then(if (choice.transparent) Modifier.transparencyPattern() else Modifier)
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    modifier = thumbnailModifier,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High
                )
            } else {
                Box(thumbnailModifier)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(choice.colour.iconResource),
                    contentDescription = null,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(colorResource(R.color.arc_launcher_background))
                        .graphicsLayer {
                            scaleX = AdaptiveIconVisibleScale
                            scaleY = AdaptiveIconVisibleScale
                        },
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = choice.colour.localizedName(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (selected) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = NuvioTheme.colors.Primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** A quiet chequer behind see-through banners, so the missing background reads as transparent. */
private fun Modifier.transparencyPattern(): Modifier = drawBehind {
    val cell = 6.dp.toPx()
    drawRect(TransparencyDark)
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) {
            drawRect(TransparencyLight, topLeft = Offset(x, y), size = Size(cell, cell))
            x += cell * 2
        }
        y += cell
        row++
    }
}

private val TransparencyDark = Color(0xFF1F1F22)
private val TransparencyLight = Color(0xFF2C2C30)

// The launcher entries are adaptive icons on API 26+, which painterResource cannot draw.
private const val AdaptiveIconVisibleScale = 108f / 72f

internal val AppIconOption.iconResource: Int
    get() = when (this) {
        AppIconOption.ORIGINAL -> R.drawable.arc_launcher_foreground
        AppIconOption.ARCTIC_BLUE -> R.drawable.arc_launcher_foreground_arctic_blue
        AppIconOption.EMERALD -> R.drawable.arc_launcher_foreground_emerald
        AppIconOption.ROSE_GOLD -> R.drawable.arc_launcher_foreground_rose_gold
        AppIconOption.COPPER -> R.drawable.arc_launcher_foreground_copper
        AppIconOption.GRAPHITE -> R.drawable.arc_launcher_foreground_graphite
        AppIconOption.GOLD -> R.drawable.arc_launcher_foreground_gold
        AppIconOption.JADE -> R.drawable.arc_launcher_foreground_jade
        AppIconOption.GLASS -> R.drawable.arc_launcher_foreground_glass
        AppIconOption.CRIMSON -> R.drawable.arc_launcher_foreground_crimson
        AppIconOption.OCEAN -> R.drawable.arc_launcher_foreground_ocean
        AppIconOption.VIOLET -> R.drawable.arc_launcher_foreground_violet
        AppIconOption.EMERALD_GREEN -> R.drawable.arc_launcher_foreground_emerald_green
        AppIconOption.AMBER -> R.drawable.arc_launcher_foreground_amber
        AppIconOption.ROSE -> R.drawable.arc_launcher_foreground_rose
        AppIconOption.WHITE -> R.drawable.arc_launcher_foreground_white
    }

@Composable
internal fun AppIconOption.localizedName(): String = when (this) {
    AppIconOption.ORIGINAL -> stringResource(R.string.appearance_app_icon_original)
    AppIconOption.ARCTIC_BLUE -> stringResource(R.string.appearance_app_icon_arctic_blue)
    AppIconOption.EMERALD -> stringResource(R.string.appearance_app_icon_mint)
    AppIconOption.ROSE_GOLD -> stringResource(R.string.appearance_app_icon_rose_gold)
    AppIconOption.COPPER -> stringResource(R.string.appearance_app_icon_copper)
    AppIconOption.GRAPHITE -> stringResource(R.string.appearance_app_icon_graphite)
    AppIconOption.GOLD -> stringResource(R.string.theme_color_gold)
    AppIconOption.JADE -> stringResource(R.string.theme_color_jade)
    AppIconOption.GLASS -> stringResource(R.string.theme_color_glass)
    AppIconOption.CRIMSON -> stringResource(R.string.theme_color_crimson)
    AppIconOption.OCEAN -> stringResource(R.string.theme_color_ocean)
    AppIconOption.VIOLET -> stringResource(R.string.theme_color_violet)
    AppIconOption.EMERALD_GREEN -> stringResource(R.string.theme_color_emerald)
    AppIconOption.AMBER -> stringResource(R.string.theme_color_amber)
    AppIconOption.ROSE -> stringResource(R.string.theme_color_rose)
    AppIconOption.WHITE -> stringResource(R.string.theme_color_white)
}

@Composable
internal fun AppIconLook.localizedName(): String = when (this) {
    AppIconLook.CLASSIC -> stringResource(R.string.appearance_app_icon_look_classic)
    AppIconLook.CARD -> stringResource(R.string.appearance_app_icon_look_card)
    AppIconLook.CARD_LETTERING -> stringResource(R.string.appearance_app_icon_look_card_lettering)
}

@Composable
internal fun AppIconChoice.localizedName(): String {
    val colourName = colour.localizedName()
    if (look == AppIconLook.CLASSIC) return colourName
    val name = stringResource(R.string.appearance_app_icon_choice, look.localizedName(), colourName)
    return if (transparent) stringResource(R.string.appearance_app_icon_choice_transparent, name) else name
}

/**
 * The banner of the choice's launcher entry, read from the manifest so the picker shows what the home screen gets.
 * It comes from the screen's own banner size, steps down by halves to no less than the card width, and is drawn with
 * high-quality filtering, off the main thread.
 */
@Composable
private fun rememberBannerThumbnail(choice: AppIconChoice, widthPx: Int): ImageBitmap? {
    val context = LocalContext.current
    val thumbnail by produceState<ImageBitmap?>(null, choice, widthPx) {
        if (widthPx <= 0) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val banner = context.packageManager.getActivityInfo(
                    ComponentName(context, launcherClassName(choice)),
                    PackageManager.MATCH_DISABLED_COMPONENTS
                ).banner
                val bounds = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    inScaled = false
                }
                BitmapFactory.decodeResource(context.resources, banner, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
                BitmapFactory.decodeResource(
                    context.resources,
                    banner,
                    BitmapFactory.Options().apply {
                        inScaled = false
                        inSampleSize = sample
                    }
                )?.asImageBitmap()
            }.getOrNull()
        }
    }
    return thumbnail
}
