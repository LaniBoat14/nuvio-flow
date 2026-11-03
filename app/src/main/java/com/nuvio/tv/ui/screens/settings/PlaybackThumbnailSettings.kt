package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.player.thumbnail.MemoryGovernor
import com.nuvio.tv.core.player.thumbnail.SeekThumbMode
import com.nuvio.tv.core.player.thumbnail.SeekThumbnailPreferences
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import com.nuvio.tv.core.player.thumbnail.ThumbPrepareMode
import com.nuvio.tv.core.player.thumbnail.ThumbnailCache
import com.nuvio.tv.core.player.thumbnail.ThumbsBeforePlay
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.components.NuvioDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Seek thumbnails: the switch, the before-play choices, the hardware decoder and the saved thumbnails. */
@Composable
internal fun PlaybackThumbnailRows(settings: PlayerSettings) {
    val context = LocalContext.current
    val internalPlayer = settings.playerPreference != PlayerPreference.EXTERNAL
    // Null until the store has answered: the rows below are not greyed out for that first frame.
    val seekThumbModeRead by SeekThumbnailPreferences.modeFlow(context)
        .collectAsState<SeekThumbMode, SeekThumbMode?>(initial = null)
    val seekThumbsOn = seekThumbModeRead != SeekThumbMode.OFF
    val seekThumbPrepareRead by SeekThumbnailPreferences.prepareModeFlow(context)
        .collectAsState<ThumbPrepareMode, ThumbPrepareMode?>(initial = null)
    val seekThumbPrepare = seekThumbPrepareRead ?: ThumbPrepareMode.OFF
    val seekThumbBeforePlay by SeekThumbnailPreferences.beforePlayFlow(context)
        .collectAsState(initial = ThumbsBeforePlay.FASTER)
    val seekThumbHardware by SeekThumbnailPreferences.hardwareFlow(context).collectAsState(initial = true)
    var seekThumbStoredBytes by remember { mutableStateOf(-1L) }
    val seekThumbScope = rememberCoroutineScope()
    var showClearSeekThumbs by remember { mutableStateOf(false) }
    var showPrepareDialog by remember { mutableStateOf(false) }
    var showBeforePlayDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        seekThumbStoredBytes = withContext(Dispatchers.IO) { SeekThumbnails.storedBytes(context) }
    }

    SettingsToggleRow(
        title = stringResource(R.string.seek_thumbnails_title),
        subtitle = stringResource(R.string.seek_thumbnails_mode_all_desc),
        checked = seekThumbModeRead == SeekThumbMode.ALL,
        onToggle = {
            val read = seekThumbModeRead ?: return@SettingsToggleRow
            seekThumbScope.launch {
                SeekThumbnailPreferences.setMode(context, if (read == SeekThumbMode.OFF) SeekThumbMode.ALL else SeekThumbMode.OFF)
            }
        },
        enabled = internalPlayer
    )
    SettingsActionRow(
        title = stringResource(R.string.seek_thumbnails_prepare_title),
        subtitle = null,
        value = stringResource(seekThumbPrepare.label),
        enabled = internalPlayer && seekThumbsOn,
        onClick = { showPrepareDialog = true }
    )
    SettingsActionRow(
        title = stringResource(R.string.seek_thumbnails_before_play_title),
        subtitle = null,
        value = stringResource(seekThumbBeforePlay.label),
        enabled = internalPlayer && seekThumbsOn && seekThumbPrepareRead != ThumbPrepareMode.OFF,
        onClick = { showBeforePlayDialog = true }
    )
    SettingsToggleRow(
        title = stringResource(R.string.seek_thumbnails_hardware_title),
        subtitle = stringResource(R.string.seek_thumbnails_hardware_subtitle),
        checked = seekThumbHardware,
        onToggle = { seekThumbScope.launch { SeekThumbnailPreferences.setHardware(context, !seekThumbHardware) } },
        enabled = internalPlayer && seekThumbsOn
    )
    val seekThumbStoredLabel = when {
        seekThumbStoredBytes < 0L -> null
        seekThumbStoredBytes == 0L -> stringResource(R.string.seek_thumbnails_stored_empty)
        seekThumbStoredBytes < 1024L * 1024L -> stringResource(R.string.seek_thumbnails_stored_under_1mb)
        else -> stringResource(
            R.string.seek_thumbnails_stored_mb,
            ((seekThumbStoredBytes + 512L * 1024L) / (1024L * 1024L)).toInt()
        )
    }
    SettingsActionRow(
        title = stringResource(R.string.seek_thumbnails_clear_title),
        subtitle = stringResource(R.string.seek_thumbnails_clear_subtitle),
        value = seekThumbStoredLabel,
        onClick = { showClearSeekThumbs = true }
    )
    if (showClearSeekThumbs) {
        ClearSeekThumbnailsDialog(
            storedLabel = seekThumbStoredLabel,
            onConfirm = {
                showClearSeekThumbs = false
                seekThumbScope.launch {
                    withContext(Dispatchers.IO) { ThumbnailCache.clearAll(context) }
                    seekThumbStoredBytes = 0L
                }
            },
            onDismiss = { showClearSeekThumbs = false }
        )
    }
    if (showPrepareDialog) {
        SeekThumbnailPrepareDialog(onDismiss = { showPrepareDialog = false })
    }
    if (showBeforePlayDialog) {
        SeekThumbnailBeforePlayDialog(onDismiss = { showBeforePlayDialog = false })
    }
}

@Composable
private fun ClearSeekThumbnailsDialog(
    storedLabel: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.seek_thumbnails_clear_title),
        subtitle = storedLabel,
        suppressFirstKeyUp = false
    ) {
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss
            )
            SettingsDialogActionButton(
                text = stringResource(R.string.action_clear),
                onClick = onConfirm,
                primary = true
            )
        }
    }
}

@Composable
private fun SeekThumbnailPrepareDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val prepare by SeekThumbnailPreferences.prepareModeFlow(context).collectAsState(initial = ThumbPrepareMode.OFF)
    val smallBox = remember(context) { MemoryGovernor.isSmallHardware(context) }
    val smallBoxNote = stringResource(R.string.seek_thumbnails_prepare_on_small_desc)
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.seek_thumbnails_prepare_title),
        subtitle = stringResource(R.string.seek_thumbnails_prepare_subtitle),
        options = ThumbPrepareMode.entries.filter { it != ThumbPrepareMode.ASK }.map {
            val description = stringResource(it.description)
            SettingsPickerOption(it, stringResource(it.label),
                if (smallBox && it != ThumbPrepareMode.OFF) "$description $smallBoxNote" else description)
        },
        selectedValue = prepare,
        onOptionSelected = { chosen ->
            scope.launch { SeekThumbnailPreferences.setPrepareMode(context, chosen) }
            onDismiss()
        },
        onDismiss = onDismiss
    )
}

@Composable
private fun SeekThumbnailBeforePlayDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val choice by SeekThumbnailPreferences.beforePlayFlow(context).collectAsState(initial = ThumbsBeforePlay.FASTER)
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.seek_thumbnails_before_play_title),
        subtitle = stringResource(R.string.seek_thumbnails_before_play_subtitle),
        options = ThumbsBeforePlay.entries.map {
            SettingsPickerOption(it, stringResource(it.label), stringResource(it.description))
        },
        selectedValue = choice,
        onOptionSelected = { chosen ->
            scope.launch { SeekThumbnailPreferences.setBeforePlay(context, chosen) }
            onDismiss()
        },
        onDismiss = onDismiss
    )
}
