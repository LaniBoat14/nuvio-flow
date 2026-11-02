@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.data.mediaserver.SERVER_DISPLAY_NAME_MAX_LENGTH
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField

@Composable
internal fun ServerDisplayNameDialog(
    connection: ServerConnection,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(connection.displayName.orEmpty()) }
    val save = {
        onSave(name)
        onDismiss()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.servers_display_name),
        subtitle = stringResource(R.string.servers_display_name_dialog_description),
        width = 640.dp
    ) {
        InputField(
            value = name,
            onValueChange = { name = it.take(SERVER_DISPLAY_NAME_MAX_LENGTH) },
            placeholder = connection.name,
            onImeAction = save,
            modifier = Modifier.fillMaxWidth()
        )
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss
            )
            if (connection.isRenamed) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.servers_display_name_reset),
                    onClick = {
                        onSave(null)
                        onDismiss()
                    }
                )
            }
            SettingsDialogActionButton(
                text = stringResource(R.string.action_save),
                onClick = save,
                primary = true
            )
        }
    }
}
