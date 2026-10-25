package com.yaya.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yaya.ai.AgentApi
import com.yaya.ai.MainActivity
import com.yaya.ai.ui.theme.MonoLabel
import com.yaya.ai.ui.theme.YayaColors
import com.yaya.ai.ui.theme.YayaiTheme
import kotlinx.coroutines.launch

/** AgentApi 的 CompositionLocal：Compose 页面经此调用执行层（AGENTS.md R5）。 */
val LocalAgentApi = staticCompositionLocalOf<AgentApi?> { null }

/** 共享 Snackbar：页面内任意位置提示（替代 Flutter 的 ScaffoldMessenger）。 */
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("未提供 SnackbarHostState") }

/** 根组件：对话页为主屏，终端/文件/MCP/设置收进左侧抽屉（右滑手势打开），无底部导航栏。 */
@OptIn(ExperimentalMaterial3Api::class)
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
            // 全屏 Surface：背景色延伸到系统栏区域，消除顶部/底部黑边
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
            var tab by rememberSaveable { mutableStateOf(0) }
            var gitOpen by rememberSaveable { mutableStateOf(false) }
            var editorPath by rememberSaveable { mutableStateOf<String?>(null) }
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                // 沉浸式：不替内容让开任何系统栏，背景由外层 Surface 铺满全屏；
                // Chat/Terminal 顶栏与输入区自行避让，其余页在此统一让开
                contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            DrawerSheet(
                                tab = tab,
                                onSelect = { t ->
                                    tab = t
                                    scope.launch { drawerState.close() }
                                },
                            )
                        },
                        gesturesEnabled = true,
                    ) {
                        when (tab) {
                            0 -> ChatScreen(
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                onOpenSettings = { tab = 4 },
                            )
                            1 -> TerminalScreen()
                            // 文件/工具页：顶部避让状态栏；底部不保护手势栏，背景平铺到手势栏下
                            else -> Box(
                                Modifier.fillMaxSize()
                                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
                            ) {
                                when (tab) {
                                    2 -> FilesScreen(onOpenFile = { editorPath = it }, onOpenGit = { gitOpen = true })
                                    3 -> McpScreen()
                                    5 -> BackupScreen()
                                    else -> SettingsScreen()
                                }
                            }
                        }
                        // Git 全屏覆盖（由文件页的 Git 按钮进入）
                        if (gitOpen) {
                            Box(
                                modifier = Modifier.fillMaxSize().background(YayaColors.Background)
                                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
                            ) {
                                GitScreen()
                                IconButton(
                                    onClick = { gitOpen = false },
                                    modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(8.dp),
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "关闭 Git")
                                }
                            }
                        }
                        // 编辑器覆盖（由文件页点文件进入）
                        editorPath?.let { path ->
                            Box(
                                Modifier.fillMaxSize()
                                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
                            ) {
                                EditorScreen(path = path, onClose = { editorPath = null })
                            }
                        }
                    }
                }
            }
            } // Surface
        }
    }
}

@Composable
private fun DrawerSheet(tab: Int, onSelect: (Int) -> Unit) {
    ModalDrawerSheet(
        modifier = Modifier.width(280.dp),
        drawerContainerColor = YayaColors.Surface,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            "YAYai",
            style = MaterialTheme.typography.titleLarge,
            color = YayaColors.Primary,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            "Android Agent",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(20.dp))
        DrawerItem(0, "对话", Icons.Filled.Chat, tab, onSelect)
        DrawerItem(1, "终端", Icons.Filled.Terminal, tab, onSelect)
        DrawerItem(2, "文件", Icons.Filled.Folder, tab, onSelect)
        DrawerItem(3, "MCP", Icons.Filled.Extension, tab, onSelect)
        DrawerItem(4, "设置", Icons.Filled.Settings, tab, onSelect)
        DrawerItem(5, "备份", Icons.Filled.Backup, tab, onSelect)
        Spacer(Modifier.weight(1f))
        Text(
            "v1.0.0",
            style = MonoLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Composable
private fun DrawerItem(index: Int, label: String, icon: ImageVector, tab: Int, onSelect: (Int) -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        selected = tab == index,
        onClick = { onSelect(index) },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/** P0 占位页：迁移完成前显示状态。 */
@Composable
private fun PlaceholderScreen(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}