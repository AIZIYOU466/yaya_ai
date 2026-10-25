import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yaya.ai.ui.components.TaskCard
import com.yaya.ai.ui.components.TaskCardState
import com.yaya.ai.ui.components.YayaCard
import com.yaya.ai.ui.theme.MonoLabel
import com.yaya.ai.ui.theme.MonoSmall
import com.yaya.ai.ui.theme.YayaiTheme
import java.io.File
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat

private data class Msg(val role: String, val text: String)

/** 模拟 ChatScreen 布局（任务卡 + 消息块 + 输入行），用于容器内 JVM 渲染验证。 */
@Composable
fun PreviewContent() {
    val msgs = listOf(
        Msg("user", "帮我运行一下项目测试，看看结果如何"),
        Msg("assistant", "好的，我先查看项目结构，然后运行测试。这可能需要几分钟时间，请稍候。"),
        Msg("tool", "调用 terminal_exec {\"cmd\":\"cargo test -p yaya-core\"}"),
        Msg("tool", "运行完成，137 个测试全部通过"),
        Msg("assistant", "测试全部通过（137 项）。完整结果如下："),
    )
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {}) { Icon(Icons.Filled.Menu, "菜单") }
            IconButton(onClick = {}) { Icon(Icons.Filled.SwitchAccount, "会话") }
            TaskCard(
                state = TaskCardState("帮我运行一下项目测试", "running", "调用 terminal_exec", 234),
                onPause = {},
                onUndo = {},
                modifier = Modifier.weight(1f),
            )
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(msgs) { PreviewRow(it) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = "",
                onValueChange = {},
                placeholder = { Text("输入指令...") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = {}) {
                Icon(Icons.AutoMirrored.Filled.Send, "发送")
            }
        }
    }
}

@Composable
private fun PreviewRow(msg: Msg) {
    val cs = MaterialTheme.colorScheme
    if (msg.role == "user") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(12.dp, 2.dp, 12.dp, 12.dp))
                    .background(cs.primary)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(msg.text, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimary)
            }
        }
        return
    }
    if (msg.role == "tool") {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .padding(top = 3.dp)
                    .width(3.dp)
                    .height(16.dp)
                    .background(cs.onSurfaceVariant.copy(alpha = 0.4f)),
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.fillMaxWidth()) {
                Text("tool", style = MonoLabel, color = cs.onSurfaceVariant)
                Text(msg.text, style = MonoSmall, color = cs.onSurface)
            }
        }
        return
    }
    YayaCard(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            msg.text,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val img = renderComposeScene(width = 822, height = 1828, density = Density(2f)) {
        YayaiTheme { PreviewContent() }
    }
    val out = File("build/preview-chat.png")
    out.parentFile?.mkdirs()
    val data = img.encodeToData(EncodedImageFormat.PNG, 100)!!
    out.writeBytes(data.bytes)
    println("PREVIEW: ${out.absolutePath} (${out.length()} bytes)")
}
