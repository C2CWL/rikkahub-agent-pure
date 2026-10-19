package me.rerere.rikkahub.ui.pages.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.ui.components.ai.WorkspaceSelectSheet
import me.rerere.rikkahub.ui.components.ui.AssistantAvatar

/** Everything the create sheet collects; nulls mean "fall back to defaults / not bound". */
data class MissionDraft(
    val title: String,
    val description: String,
    val goals: List<String>,
    val workspaceId: String?,      // null = 不绑定
    val assistantId: String?,      // null = 默认助手
    val approvalMode: String?,     // null = 默认（继承全局）；或 "AUTO"/"SEMI"/"MANUAL"
    val maxResumeRounds: Int?,     // 高级；null = 均衡默认
    val stallRounds: Int?,
    val tokenBudget: Long?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateMissionSheet(
    settings: Settings,
    workspaces: List<WorkspaceEntity>,
    onDismiss: () -> Unit,
    defaultConversationId: String? = null,
    onCreate: (MissionDraft) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val criteria = remember { mutableStateListOf<String>() }
    var assistantId by remember { mutableStateOf<String?>(null) }
    var workspaceId by remember { mutableStateOf<String?>(null) }
    var approvalMode by remember { mutableStateOf<String?>(null) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var maxResumeRounds by remember { mutableStateOf("") }
    var stallRounds by remember { mutableStateOf("") }
    var tokenBudget by remember { mutableStateOf("") }
    var showAssistantSheet by remember { mutableStateOf(false) }
    var showWorkspaceSheet by remember { mutableStateOf(false) }

    val selectedAssistant = settings.assistants.firstOrNull { it.id.toString() == assistantId }
    val workspaceSheetAssistant = selectedAssistant ?: settings.assistants.firstOrNull()
    val selectedWorkspace = workspaces.firstOrNull { it.id == workspaceId }

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

            // 执行助手
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAssistantSheet = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.mission_create_assistant),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = selectedAssistant?.name?.ifEmpty { null }
                        ?: stringResource(R.string.mission_create_assistant_default),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 工作区
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = workspaceSheetAssistant != null) {
                        showWorkspaceSheet = true
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.mission_create_workspace),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = selectedWorkspace?.name
                        ?: stringResource(R.string.mission_create_workspace_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 审批模式
            Text(
                text = stringResource(R.string.mission_create_approval_mode),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val options = listOf<Pair<String?, Int>>(
                    null to R.string.mission_approval_default,
                    "AUTO" to R.string.mission_approval_auto,
                    "SEMI" to R.string.mission_approval_semi,
                    "MANUAL" to R.string.mission_approval_manual,
                )
                options.forEach { (value, labelRes) ->
                    FilterChip(
                        selected = approvalMode == value,
                        onClick = { approvalMode = value },
                        label = { Text(text = stringResource(labelRes)) },
                    )
                }
            }

            // 高级选项
            TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                Text(text = stringResource(R.string.mission_create_advanced))
            }
            if (advancedExpanded) {
                Text(
                    text = stringResource(R.string.mission_create_advanced_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = maxResumeRounds,
                    onValueChange = { maxResumeRounds = it.filter(Char::isDigit) },
                    label = { Text(text = stringResource(R.string.mission_create_max_resume_rounds)) },
                    placeholder = { Text(text = "5") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = stallRounds,
                    onValueChange = { stallRounds = it.filter(Char::isDigit) },
                    label = { Text(text = stringResource(R.string.mission_create_stall_rounds)) },
                    placeholder = { Text(text = "2") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = tokenBudget,
                    onValueChange = { tokenBudget = it.filter(Char::isDigit) },
                    label = { Text(text = stringResource(R.string.mission_create_token_budget)) },
                    placeholder = { Text(text = "500000") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            Button(
                onClick = {
                    onCreate(
                        MissionDraft(
                            title = title.trim(),
                            description = description.trim(),
                            goals = criteria.map { it.trim() }.filter { it.isNotEmpty() },
                            workspaceId = workspaceId,
                            assistantId = assistantId,
                            approvalMode = approvalMode,
                            maxResumeRounds = maxResumeRounds.toIntOrNull(),
                            stallRounds = stallRounds.toIntOrNull(),
                            tokenBudget = tokenBudget.toLongOrNull(),
                        )
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

    // 执行助手选择
    if (showAssistantSheet) {
        ModalBottomSheet(onDismissRequest = { showAssistantSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.mission_select_assistant),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ListItem(
                    headlineContent = {
                        Text(text = stringResource(R.string.mission_create_assistant_default))
                    },
                    modifier = Modifier.clickable {
                        assistantId = null
                        showAssistantSheet = false
                    },
                )
                settings.assistants.forEach { assistant ->
                    ListItem(
                        headlineContent = { Text(text = assistant.name) },
                        leadingContent = { AssistantAvatar(assistant = assistant) },
                        modifier = Modifier.clickable {
                            assistantId = assistant.id.toString()
                            showAssistantSheet = false
                        },
                    )
                }
            }
        }
    }

    // 工作区选择
    if (showWorkspaceSheet && workspaceSheetAssistant != null) {
        WorkspaceSelectSheet(
            assistant = workspaceSheetAssistant,
            workspaces = workspaces,
            onSelect = { id ->
                workspaceId = id
                showWorkspaceSheet = false
            },
            onManage = {},
            onDismiss = { showWorkspaceSheet = false },
        )
    }
}
