package com.yaya.ai.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.yaya.ai.ui.theme.YayaColors

/** 节点状态点（纲领：灰 / 黄 / 绿 / 红）；running 态脉动呼吸（非转圈）。 */
@Composable
fun StatusDot(status: String, modifier: Modifier = Modifier) {
    val color = when (status) {
        "running", "starting", "generating" -> YayaColors.StatusRunning
        "done", "success" -> YayaColors.StatusSuccess
        "error" -> YayaColors.StatusError
        else -> YayaColors.StatusPending
    }
    val active = status == "running" || status == "starting" || status == "generating"
    if (active) {
        val transition = rememberInfiniteTransition(label = "statusDot")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "statusDotAlpha",
        )
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(color)
                .alpha(alpha)
                .size(8.dp),
        )
    } else {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(color)
                .size(8.dp),
        )
    }
}
