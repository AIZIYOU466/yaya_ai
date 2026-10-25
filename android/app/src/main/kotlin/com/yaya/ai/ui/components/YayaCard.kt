package com.yaya.ai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.yaya.ai.ui.theme.YayaColors

/** 细描边卡片：8dp 小圆角 + 1dp 半透明描边 + 半透明 surface，无阴影（纲领）。 */
@Composable
fun YayaCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(YayaColors.SurfaceVariant.copy(alpha = 0.7f))
            .border(BorderStroke(1.dp, YayaColors.Outline.copy(alpha = 0.6f)), RoundedCornerShape(8.dp)),
    ) {
        content()
    }
}
