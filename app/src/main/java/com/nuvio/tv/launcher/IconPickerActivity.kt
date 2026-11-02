@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.launcher

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.MemberAccessDataStore
import com.nuvio.tv.domain.model.AppIconChoice
import com.nuvio.tv.domain.model.AppIconLook
import com.nuvio.tv.domain.model.AppIconOption
import com.nuvio.tv.domain.model.CosmeticEntitlements
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.settings.localizedName
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Icon picker for launchers (Projectivy and the ADW/Nova family): shows the transparent banners and hands the chosen
 * one back as a resource link plus a small bitmap. Supporter colours follow the same rule as the in-app picker.
 */
@AndroidEntryPoint
class IconPickerActivity : ComponentActivity() {
    @Inject
    lateinit var memberAccessDataStore: MemberAccessDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (callingActivity == null) {
            finish()
            return
        }
        setResult(RESULT_CANCELED)
        val active = activeAppIcon(this) ?: AppIconChoice(AppIconOption.ORIGINAL)
        val entitlements = runCatching { memberAccessDataStore.getLastKnown()?.entitlements }.getOrNull()
            ?: CosmeticEntitlements.None
        val colours = AppIconOption.entries.filter { colour ->
            val entitlement = colour.entitlement
            entitlement == null || entitlements.includes(entitlement) || colour == active.colour
        }
        val focus = AppIconChoice(
            active.colour,
            if (active.look == AppIconLook.CARD) AppIconLook.CARD else AppIconLook.CARD_LETTERING,
            transparent = true
        )
        setContent {
            NuvioTheme {
                IconPickerScreen(colours = colours, focus = focus, onPicked = ::deliver)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun deliver(choice: AppIconChoice) {
        val resId = iconPackDrawable(this, choice)
        if (resId == 0) return
        val result = Intent().putExtra(
            Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
            Intent.ShortcutIconResource.fromContext(this, resId)
        )
        resultBitmap(resId)?.let { result.putExtra("icon", it) }
        setResult(RESULT_OK, result)
        finish()
    }

    /** Kept well under the binder limit; launchers that follow the resource link get the full size. */
    private fun resultBitmap(resId: Int): Bitmap? = runCatching {
        val full = BitmapFactory.decodeResource(resources, resId, BitmapFactory.Options().apply { inScaled = false })
            ?: return null
        if (full.width <= ResultWidth) return full
        val height = full.height * ResultWidth / full.width
        Bitmap.createScaledBitmap(full, ResultWidth, height, true).also { if (it !== full) full.recycle() }
    }.getOrNull()
}

private const val ResultWidth = 480
private const val Columns = 4

@Composable
private fun IconPickerScreen(
    colours: List<AppIconOption>,
    focus: AppIconChoice,
    onPicked: (AppIconChoice) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocusAfterFrames() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        iconPackLooks.forEach { look ->
            Text(
                text = look.localizedName(),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary
            )
            colours.chunked(Columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    row.forEach { colour ->
                        val choice = AppIconChoice(colour, look, transparent = true)
                        key(look, colour) {
                            BannerCard(
                                choice = choice,
                                onClick = { onPicked(choice) },
                                modifier = Modifier
                                    .weight(1f)
                                    .then(if (choice == focus) Modifier.focusRequester(focusRequester) else Modifier)
                            )
                        }
                    }
                    repeat(Columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun BannerCard(choice: AppIconChoice, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            val thumbnail = rememberThumbnail(choice)
            val imageModifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    modifier = imageModifier,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High
                )
            } else {
                Box(imageModifier)
            }
            Text(
                text = choice.colour.localizedName(),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun rememberThumbnail(choice: AppIconChoice): ImageBitmap? {
    val context = LocalContext.current
    val thumbnail by produceState<ImageBitmap?>(null, choice) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val resId = iconPackDrawable(context, choice)
                if (resId == 0) return@runCatching null
                BitmapFactory.decodeResource(
                    context.resources,
                    resId,
                    BitmapFactory.Options().apply { inSampleSize = 2 }
                )?.asImageBitmap()
            }.getOrNull()
        }
    }
    return thumbnail
}
