package me.rerere.rikkahub.ui.pages.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.rikkahub.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateMissionSheet(
    onDismiss: () -> Unit,
    defaultConversationId: String? = null,
    onCreate: (title: String, description: String, acceptance: List<String>, bindCurrent: Boolean) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val criteria = remember { mutableStateListOf<String>() }
    var bindCurrent by remember { mutableStateOf(defaultConversationId != null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.mission_create_title),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(text = stringResource(R.string.mission_create_field_title)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(text = stringResource(R.string.mission_create_field_description)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Text(
                text = stringResource(R.string.mission_create_acceptance),
                style = MaterialTheme.typography.titleSmall,
            )
            criteria.forEachIndexed { index, value ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { criteria[index] = it },
                        label = {
                            Text(text = stringResource(R.string.mission_create_acceptance_hint, index + 1))
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    IconButton(onClick = { criteria.removeAt(index) }) {
                        Icon(HugeIcons.Cancel01, contentDescription = null)
                    }
                }
            }
            TextButton(onClick = { criteria.add("") }) {
                Icon(HugeIcons.Add01, contentDescription = null)
                Text(
                    text = stringResource(R.string.mission_create_add_acceptance),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            if (defaultConversationId != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.mission_create_bind_current),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = bindCurrent, onCheckedChange = { bindCurrent = it })
                }
            }
            Button(
                onClick = {
                    onCreate(
                        title.trim(),
                        description.trim(),
                        criteria.map { it.trim() }.filter { it.isNotEmpty() },
                        bindCurrent,
                    )
                    onDismiss()
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.mission_create_confirm))
            }
        }
    }
}
