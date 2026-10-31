package com.yaya.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yaya.ai.ui.formatCost
import com.yaya.ai.ui.formatTokens
import com.yaya.ai.ui.theme.MonoLabel
import com.yaya.ai.ui.theme.MonoSmall
import com.yaya.ai.ui.theme.YayaColors

/** 子代理运行态（任务 32）：由 `Event::Subagent` 事件流推导，点击可查看子会话详情。 */
data class SubagentBadge(
    val id: String,
    val agent: String,
    val phase: String, // started / finished / failed
    val task: String = "",
    val detail: String = "",
)

/** 任务卡状态（ChatScreen 从事件流推导，纯 Kotlin 不碰 core）。 */
data class TaskCardState(
    val goal: String = "",
    val status: String = "idle", // idle / starting / running / done / error
    val lastStep: String = "",
    val tokens: Int = 0,
    val mode: String = "build", // build / plan / auto（R12 运行模式）
    val model: String = "",
    val startedAt: Long = 0L, // 运行开始时间戳（本地计时，不依赖 core）
    val costUsd: Double? = null, // 任务 30：会话累计费用估算（未配置单价时为 null）
    val subagents: List<SubagentBadge> = emptyList(), // 任务 32：并行子代理状态
)

/** 任务卡（46 决策核心组件）：目标 + 状态点 + 最近一步 + 用量 + 操作位。 */
@Composable
fun TaskCard(
    state: TaskCardState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSubagent: (SubagentBadge) -> Unit = {},
) {
    val suspended = state.status == "suspended"
    YayaCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(state.status)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.goal.isBlank()) "就绪" else state.goal,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state.tokens > 0) {
                    Text(
                        text = "${formatTokens(state.tokens)} tok",
                        style = MonoLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                state.costUsd?.takeIf { it > 0 }?.let {
                    Text(
                        text = "≈\$${formatCost(it)}",
                        style = MonoLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Elapsed(state.status, state.startedAt)
                ModeChip(state.mode)
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onUndo, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.History,
                        contentDescription = "撤销到检查点",
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(
                    onClick = if (suspended) onResume else onPause,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        if (suspended) Icons.Filled.PlayArrow else Icons.Filled.Stop,
                        contentDescription = if (suspended) "继续任务" else "暂停任务",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (state.lastStep.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("▸ ", style = MonoSmall, color = YayaColors.Primary)
                    Text(
                        text = state.lastStep,
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.model.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = state.model,
                    style = MonoLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 任务 32：并行子代理状态（点击进入子会话查看任务与结果）。
            if (state.subagents.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.subagents.forEach { badge ->
                        val running = badge.phase == "started"
                        val tone = when (badge.phase) {
                            "failed" -> YayaColors.StatusError
                            "finished" -> YayaColors.StatusSuccess
                            else -> YayaColors.StatusRunning
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(YayaColors.SurfaceHigh)
                                .clickable { onOpenSubagent(badge) }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(tone),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "${badge.agent} · " +
                                    (if (running) "运行中" else badge.phase),
                                style = MonoLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 运行耗时：running 态每秒刷新，显示相对启动的时间；非运行态不显示。 */
@Composable
private fun Elapsed(status: String, startedAt: Long) {
    val active = status == "running" || status == "starting" || status == "streaming"
    if (!active || startedAt == 0L) return
    var now by remember(startedAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt, active) {
        while (active) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val sec = ((now - startedAt) / 1000).toInt()
    val label = if (sec >= 60) "${sec / 60}m${sec % 60}s" else "${sec}s"
    Text(
        text = label,
        style = MonoLabel,
        color = YayaColors.StatusRunning,
    )
    Spacer(Modifier.width(6.dp))
}

/** 运行模式 chip（R12：BUILD 确认执行 / PLAN 只读 / AUTO 免授权）。 */
@Composable
private fun ModeChip(mode: String) {
    val (label, bg, fg) = when (mode) {
        "plan" -> Triple("PLAN", YayaColors.SurfaceHigh, YayaColors.TextSecondary)
        "auto" -> Triple("AUTO", YayaColors.StatusSuccess.copy(alpha = 0.15f), YayaColors.StatusSuccess)
        else -> Triple("BUILD", YayaColors.PrimaryContainer, YayaColors.OnPrimaryContainer)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = MonoLabel, color = fg)
    }
}
