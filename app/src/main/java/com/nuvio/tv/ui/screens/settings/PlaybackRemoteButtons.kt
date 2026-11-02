package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.RemoteButtonBinding
import com.nuvio.tv.data.local.RemoteButtonJob
import com.nuvio.tv.data.local.RemoteButtonMap
import com.nuvio.tv.data.local.RemoteKeyKind
import com.nuvio.tv.data.local.RemotePressKind
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.player.RemotePressTracker
import com.nuvio.tv.ui.screens.player.remoteBindingLabel
import com.nuvio.tv.ui.screens.player.remoteJobDescription
import com.nuvio.tv.ui.screens.player.remoteJobLabel
import com.nuvio.tv.ui.screens.player.remoteKeyLabel
import com.nuvio.tv.ui.screens.player.remoteSkipSizeLabel
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private const val LEARN_HINT_DELAY_MS = 10_000L

@Composable
internal fun PlaybackRemoteButtonsSection(
    map: RemoteButtonMap,
    onChange: (RemoteButtonMap) -> Unit
) {
    val context = LocalContext.current
    var learning by remember { mutableStateOf(false) }
    var numberKeys by remember { mutableStateOf(false) }
    var kindFor by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<Pair<Int, RemotePressKind>?>(null) }
    var sizeFor by remember { mutableStateOf<Triple<Int, RemotePressKind, RemoteButtonJob>?>(null) }

    RemoteButtonsNote(stringResource(R.string.remote_buttons_intro))
    SettingsActionRow(
        title = stringResource(R.string.remote_buttons_setup_title),
        subtitle = stringResource(R.string.remote_buttons_setup_subtitle),
        leadingIcon = Icons.Default.Add,
        onClick = { learning = true }
    )
    map.rowKeys.forEach { key ->
        if (key == RemoteButtonMap.NUMBER_KEYS_ROW) {
            SettingsActionRow(
                title = stringResource(R.string.remote_buttons_number_keys),
                subtitle = stringResource(R.string.remote_buttons_number_keys_desc),
                value = remoteJobLabel(
                    context,
                    if (map.numberKeysJump) RemoteButtonJob.JUMP_TO_PERCENT else RemoteButtonJob.NOTHING
                ),
                onClick = { numberKeys = true }
            )
        } else {
            SettingsActionRow(
                title = remoteKeyLabel(context, key),
                subtitle = remoteRowSubtitle(context, map, key),
                value = remotePressValue(context, map, key),
                onClick = { kindFor = key }
            )
        }
    }
    SettingsActionRow(
        title = stringResource(R.string.remote_buttons_reset),
        subtitle = null,
        trailingIcon = null,
        enabled = map != RemoteButtonMap.DEFAULT,
        onClick = { onChange(RemoteButtonMap.DEFAULT) }
    )
    RemoteButtonsNote(stringResource(R.string.remote_buttons_mapper_note))
    RemoteButtonsNote(stringResource(R.string.remote_buttons_hold_note))

    if (learning) {
        RemoteButtonLearnDialog(
            onDismiss = { learning = false },
            onLearned = { key, kind ->
                learning = false
                editing = key to kind
            }
        )
    }

    if (numberKeys) {
        val options = listOf(RemoteButtonJob.JUMP_TO_PERCENT, RemoteButtonJob.NOTHING)
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.remote_buttons_job_title, stringResource(R.string.remote_buttons_number_keys)),
            options = options.map { SettingsPickerOption(it, remoteJobLabel(context, it), remoteJobDescription(context, it)) },
            selectedValue = if (map.numberKeysJump) RemoteButtonJob.JUMP_TO_PERCENT else RemoteButtonJob.NOTHING,
            onOptionSelected = {
                onChange(map.withNumberKeysJump(it == RemoteButtonJob.JUMP_TO_PERCENT))
                numberKeys = false
            },
            onDismiss = { numberKeys = false }
        )
    }

    kindFor?.let { key ->
        val hold = map.holdFor(key)
        val options = buildList<SettingsPickerOption<RemotePressKind?>> {
            add(
                SettingsPickerOption(
                    RemotePressKind.PRESS, stringResource(R.string.remote_buttons_press),
                    remotePressValue(context, map, key)
                )
            )
            add(
                SettingsPickerOption(
                    RemotePressKind.HOLD, stringResource(R.string.remote_buttons_hold),
                    hold?.let { remoteBindingLabel(context, it) } ?: stringResource(R.string.remote_buttons_hold_not_set)
                )
            )
            if (map.isCustomKey(key)) add(SettingsPickerOption(null, stringResource(R.string.remote_buttons_remove)))
        }
        val kindNote = stringResource(R.string.remote_buttons_kind_subtitle)
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.remote_buttons_kind_title, remoteKeyLabel(context, key)),
            subtitle = if (RemoteButtonMap.isHiddenControlsKey(key)) {
                stringResource(R.string.remote_buttons_up_down_desc) + " " + kindNote
            } else kindNote,
            options = options,
            selectedValue = RemotePressKind.PRESS,
            width = 480.dp,
            onOptionSelected = { kind ->
                kindFor = null
                if (kind == null) onChange(map.without(key)) else editing = key to kind
            },
            onDismiss = { kindFor = null }
        )
    }

    editing?.let { (key, kind) ->
        val current = map.bindingFor(key, kind) ?: RemoteButtonBinding.NOTHING
        val usualPress = kind == RemotePressKind.PRESS && RemoteButtonMap.isHiddenControlsKey(key)
        val options = RemoteButtonMap.jobsFor(key, kind).map {
            if (usualPress && it == RemoteButtonJob.NOTHING) {
                SettingsPickerOption(it, stringResource(R.string.remote_buttons_hold_not_set))
            } else {
                SettingsPickerOption(it, remoteJobLabel(context, it), remoteJobDescription(context, it, kind))
            }
        }
        SettingsSingleChoiceDialog(
            title = stringResource(
                if (kind == RemotePressKind.HOLD) R.string.remote_buttons_hold_job_title else R.string.remote_buttons_job_title,
                remoteKeyLabel(context, key)
            ),
            subtitle = if (RemoteButtonMap.isHiddenControlsKey(key)) stringResource(R.string.remote_buttons_up_down_desc) else null,
            options = options,
            selectedValue = current.job,
            width = 480.dp,
            maxHeight = 380.dp,
            onOptionSelected = { job ->
                editing = null
                if (job.needsSkipSize) sizeFor = Triple(key, kind, job)
                else onChange(map.with(key, kind, RemoteButtonBinding(job)))
            },
            onDismiss = { editing = null }
        )
    }

    sizeFor?.let { (sizeKey, kind, job) ->
        val current = map.bindingFor(sizeKey, kind)?.takeIf { it.job == job }?.skipMs ?: RemoteButtonMap.DEFAULT_SKIP_MS
        SettingsSingleChoiceDialog(
            title = stringResource(
                if (kind == RemotePressKind.HOLD) R.string.remote_buttons_hold_skip_size_title else R.string.remote_buttons_skip_size_title,
                remoteKeyLabel(context, sizeKey)
            ),
            subtitle = stringResource(R.string.remote_buttons_skip_size_subtitle),
            options = RemoteButtonMap.SKIP_SIZES_MS.map { SettingsPickerOption(it, remoteSkipSizeLabel(context, it)) },
            selectedValue = current,
            onOptionSelected = { ms ->
                onChange(map.with(sizeKey, kind, RemoteButtonBinding(job, ms)))
                sizeFor = null
            },
            onDismiss = { sizeFor = null }
        )
    }
}

/** Up and Down without a press job keep showing the controls, so they read "Not set" rather than "Do nothing". */
private fun remotePressValue(context: Context, map: RemoteButtonMap, key: Int): String =
    map.bindingFor(key)?.let { remoteBindingLabel(context, it) } ?: context.getString(
        if (RemoteButtonMap.isHiddenControlsKey(key)) R.string.remote_buttons_hold_not_set else R.string.remote_buttons_job_nothing
    )

/** The hold job first, then where the button came from. */
private fun remoteRowSubtitle(context: Context, map: RemoteButtonMap, key: Int): String? {
    val parts = listOfNotNull(
        map.holdFor(key)?.let { context.getString(R.string.remote_buttons_hold_value, remoteBindingLabel(context, it)) },
        when {
            RemoteButtonMap.isHiddenControlsKey(key) -> context.getString(R.string.remote_buttons_up_down_row)
            map.isCustomKey(key) -> context.getString(R.string.remote_buttons_added)
            else -> null
        }
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun RemoteButtonsNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.colors.TextSecondary,
        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)
    )
}

@Composable
private fun RemoteButtonLearnDialog(
    onDismiss: () -> Unit,
    onLearned: (Int, RemotePressKind) -> Unit
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val tracker = remember { RemotePressTracker(ViewConfiguration.getLongPressTimeout().toLong()) }
    var message by remember { mutableStateOf<String?>(null) }
    var waitedLong by remember { mutableStateOf(false) }
    var holdSeen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focusRequester.requestFocusAfterFrames() }
    LaunchedEffect(Unit) {
        delay(LEARN_HINT_DELAY_MS)
        waitedLong = true
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.remote_buttons_learn_title),
        subtitle = stringResource(R.string.remote_buttons_learn_subtitle)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    val code = native.keyCode
                    if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BUTTON_B) {
                        return@onPreviewKeyEvent false
                    }
                    when (RemoteButtonMap.kindOf(code)) {
                        RemoteKeyKind.IGNORED -> false
                        RemoteKeyKind.VOLUME -> {
                            if (native.action == KeyEvent.ACTION_DOWN) {
                                message = context.getString(R.string.remote_buttons_learn_volume)
                            }
                            false
                        }
                        RemoteKeyKind.LOCKED -> {
                            if (native.action == KeyEvent.ACTION_DOWN) {
                                message = context.getString(R.string.remote_buttons_learn_locked, remoteKeyLabel(context, code))
                            }
                            true
                        }
                        RemoteKeyKind.ASSIGNABLE, RemoteKeyKind.HIDDEN_CONTROLS -> {
                            val target = RemoteButtonMap.learnTarget(code)
                            if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                                tracker.start(code, native.eventTime)
                                holdSeen = false
                                message = null
                            } else if (tracker.isTracking(code)) {
                                // The job list opens once the button is let go, so none of its repeats land there.
                                when (tracker.onKey(native)) {
                                    RemotePressTracker.Outcome.HOLD -> if (native.action == KeyEvent.ACTION_UP) {
                                        onLearned(target, RemotePressKind.HOLD)
                                    } else {
                                        holdSeen = true
                                        message = context.getString(R.string.remote_buttons_learn_let_go)
                                    }
                                    RemotePressTracker.Outcome.RELEASED -> if (holdSeen) onLearned(target, RemotePressKind.HOLD)
                                    RemotePressTracker.Outcome.PRESS -> onLearned(target, RemotePressKind.PRESS)
                                    else -> Unit
                                }
                            }
                            true
                        }
                    }
                }
        ) {
            Text(
                text = message ?: if (waitedLong) {
                    stringResource(R.string.remote_buttons_learn_nothing)
                } else {
                    stringResource(R.string.remote_buttons_learn_waiting)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(vertical = NuvioTheme.spacing.md)
            )
        }
    }
}
