package me.rerere.rikkahub.ui.pages.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.mission.acceptanceChecked
import me.rerere.rikkahub.data.mission.acceptanceCriteria
import me.rerere.rikkahub.data.mission.statusEnum

@Composable
fun missionStatusLabel(status: MissionStatus): String = stringResource(
    when (status) {
        MissionStatus.ACTIVE -> R.string.mission_status_active
        MissionStatus.BLOCKED -> R.string.mission_status_blocked
        MissionStatus.REVIEW -> R.string.mission_status_review
        MissionStatus.DONE -> R.string.mission_status_done
        MissionStatus.ARCHIVED -> R.string.mission_status_archived
    }
)

@Composable
fun missionStatusColors(status: MissionStatus): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        MissionStatus.ACTIVE -> scheme.primaryContainer to scheme.onPrimaryContainer
        MissionStatus.BLOCKED -> scheme.errorContainer to scheme.onErrorContainer
        MissionStatus.REVIEW -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        MissionStatus.DONE -> scheme.secondaryContainer to scheme.onSecondaryContainer
        MissionStatus.ARCHIVED -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
}

@Composable
fun StatusPill(status: MissionStatus, modifier: Modifier = Modifier) {
    val (container, content) = missionStatusColors(status)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
    ) {
        Text(
            text = missionStatusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun MissionCard(
    mission: MissionEntity,
    subAgentCount: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (statusColor, _) = missionStatusColors(mission.statusEnum)
    val criteria = mission.acceptanceCriteria
    val checked = mission.acceptanceChecked
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(statusColor)
            )
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = mission.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusPill(status = mission.statusEnum)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (criteria.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.mission_acceptance_progress,
                                checked.size,
                                criteria.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (mission.artifactCount > 0) {
                        Text(
                            text = stringResource(R.string.mission_artifacts_count, mission.artifactCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = relativeTime(mission.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun StatBlock(
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun relativeTime(epochMs: Long): String {
    val diffMinutes = (System.currentTimeMillis() - epochMs).coerceAtLeast(0) / 60_000
    return when {
        diffMinutes < 1 -> stringResource(R.string.mission_time_just_now)
        diffMinutes < 60 -> stringResource(R.string.mission_time_minutes_ago, diffMinutes)
        diffMinutes < 60 * 24 -> stringResource(R.string.mission_time_hours_ago, diffMinutes / 60)
        else -> stringResource(R.string.mission_time_days_ago, diffMinutes / (60 * 24))
    }
}
