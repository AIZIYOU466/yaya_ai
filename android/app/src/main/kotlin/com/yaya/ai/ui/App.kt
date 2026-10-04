package com.yaya.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yaya.ai.AgentApi
import com.yaya.ai.MainActivity

/** AgentApi 的 CompositionLocal：Compose 页面经此调用执行层（AGENTS.md R5）。 */
val LocalAgentApi = staticCompositionLocalOf<AgentApi?> { null }

/** 共享 Snackbar：页面内任意位置提示（替代 Flutter 的 ScaffoldMessenger）。 */
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("未提供 SnackbarHostState") }

/** 主题色板（UI_DESIGN_LEADER v1.0）：主色 #0A0E14、强调 #00E5A0、警示 #FF5F56/#FFB400。 */
private val YayaiColors = darkColorScheme(
    primary = Color(0xFF00E5A0),
    onPrimary = Color(0xFF00201A),
    background = Color(0xFF0A0E14),
    surface = Color(0xFF0E141C),
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0),
    error = Color(0xFFFF5F56),
    secondary = Color(0xFFFFB400),
)

@Composable
fun YayaiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = YayaiColors, content = content)
}

/** 根组件：底部导航 + 页面容器。P0 三个 tab（聊天/文件/设置），其余页面 P1-P3 补。 */
@Composable
fun App() {
    val context = LocalContext.current
    val api = context as? AgentApi ?: context as? MainActivity
    val snackbarHostState = remember { SnackbarHostState() }
    CompositionLocalProvider(
        LocalAgentApi provides api,
        LocalSnackbar provides snackbarHostState,
    ) {
        YayaiTheme {
            var tab by rememberSaveable { mutableStateOf(0) }
            var gitOpen by rememberSaveable { mutableStateOf(false) }
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(Icons.Filled.Chat, contentDescription = null) },
                            label = { Text("聊天") },
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                            label = { Text("终端") },
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                            label = { Text("文件") },
                        )
                        NavigationBarItem(
                            selected = tab == 3,
                            onClick = { tab = 3 },
                            icon = { Icon(Icons.Filled.Extension, contentDescription = null) },
                            label = { Text("MCP") },
                        )
                        NavigationBarItem(
                            selected = tab == 4,
                            onClick = { tab = 4 },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            label = { Text("设置") },
                        )
                    }
                },
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    when (tab) {
                        0 -> PlaceholderScreen("聊天页（迁移中：P3 落地）")
                        1 -> TerminalScreen()
                        2 -> FilesScreen(onOpenGit = { gitOpen = true })
                        3 -> McpScreen()
                        else -> SettingsScreen()
                    }
                    // Git 全屏覆盖（由文件页的 Git 按钮进入）
                    if (gitOpen) {
                        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0E14))) {
                            GitScreen()
                            IconButton(
                                onClick = { gitOpen = false },
                                modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(8.dp),
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "关闭 Git")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** P0 占位页：迁移完成前显示状态。 */
@Composable
private fun PlaceholderScreen(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}