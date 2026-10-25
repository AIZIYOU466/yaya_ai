package com.yaya.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yaya.ai.ui.ModelCaps
import com.yaya.ai.ui.formatPrice
import com.yaya.ai.ui.theme.MonoLabel
import com.yaya.ai.ui.theme.YayaColors

/** 模型能力标签行（任务 25）：tools / image / 上下文窗口。 */
@Composable
fun CapsTags(caps: ModelCaps, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (caps.tools) Tag("tools")
        if (caps.vision) Tag("image")
        caps.contextLabel()?.let { Tag("$it ctx") }
        if (caps.hasPricing()) {
            Tag("\$${formatPrice(caps.inputCostPerM)}/\$${formatPrice(caps.outputCostPerM)} per M")
        }
    }
}

@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MonoLabel,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(YayaColors.SurfaceHigh.copy(alpha = 0.7f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}
