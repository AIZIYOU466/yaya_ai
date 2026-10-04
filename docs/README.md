# YAYai 使用文档

YAYai 是运行在 Android 上的 AI Agent 客户端：AI 对话 + 内置 proot 终端 + MCP 工具 + 自动记忆/技能/子代理。本文档分三部分：功能说明、快速开始、进阶教程。

## 功能说明

### 文件浏览与代码编辑器

- **文件树**：「文件」tab 展示工作区缩进树形目录，目录在前、文件在后；点目录展开/折叠，点文件打开编辑器；长按可新建文件（可含路径）、重命名、删除（删除不可恢复，会二次确认）。
- **编辑器**：等宽字体、撤销/重做（最多 100 步）、保存、快捷符号栏（Tab/空格/常用符号）、未保存退出确认；Markdown 默认预览渲染，其余代码文件默认编辑，可切换「预览」查看语法高亮（纯 Dart 零依赖，支持 Dart/Kotlin/Java/Python/JS/JSON/Shell/Rust/C/YAML/XML/Markdown）。

### AI Agent 闭环

### Git 版本管理

- **入口**：「文件」页右上角的 Git 图标。
- **状态**：已暂存 / 已修改 / 未跟踪三组展示（`git status --porcelain -z` 解析）；可暂存/取消暂存/查看 diff/回退单个文件，全部暂存、全部取消暂存、全部回退、提交。
- **分支**：当前分支高亮，可切换/新建/删除（删除仅限已合并分支）。
- **提交**：`git log --oneline` 最近 50 条，点开查看 `git show --stat` 详情。
- **前置**：经 proot 容器执行 git（工作区已挂载进容器 `/workspace`），需容器安装 git（Alpine 执行 `apk add git`）；未安装时页面会提示。

### AI Agent 闭环

- **ReAct 多步循环**（Rust Core）：观察 → 决策 → 流式生成 → 执行工具 → 回填结果，支持最大步数与中断取消。
- **工具集**（模型可调用）：
  - `terminal_exec` — 在 proot Linux 容器中执行 shell 命令（带超时）
  - `clipboard_read` / `clipboard_write` — 剪贴板读写
  - `notify` — 发送本地通知
  - `mcp__<server>__<tool>` — 已启用 MCP 服务器的工具
  - `memory_list` / `memory_read` / `memory_save` / `memory_edit` / `memory_delete` — 长期记忆
  - `subagent` — 派生子代理执行子任务
- **运行模式**（右上角切换）：
  - `BUILD` — 正常开发：不可逆操作（终端命令、MCP 工具）执行前弹窗确认
  - `PLAN` — 只读规划：写操作一律拦截并返回明确原因
  - `AUTO` — 免授权：跳过所有确认
- **Token 统计**：会话结束显示累计 token（右上角）。
- **检查点与回滚**：每次工具调用前自动保存对话快照，右上角「历史」按钮可回滚到任意检查点。

### 本地持久化

会话、消息、检查点、记忆由 SQLite 持久化；进程被杀后重启自动恢复最近会话的对话历史。

### 子代理

`subagent` 工具以独立上下文运行子任务（顺序执行），子任务内部工具同样受运行模式策略约束（BUILD 下不可逆操作仍会确认）。子代理结果以工具结果形式回填主对话。

## 快速开始

1. **安装 APK**：从 Release 下载 `armsolo`（arm64 真机）或 `universal` 包安装。
2. **配置模型**：进入「设置」，填入 OpenAI 兼容的 Base URL、API Key 与模型名（或配置端侧 .gguf 模型路径）。
3. **安装 Linux 容器**：「设置 → 容器与镜像」选择并安装镜像（内置 Alpine，首次会自动下载）。
4. **开始对话**：打开聊天页输入指令，如「运行测试」「查看项目结构」「写一段代码」。
5. **切换模式**：运行中任务结束后，点右上角模式按钮在 BUILD / PLAN / AUTO 间切换。

### 授权确认

在 BUILD 模式下，Agent 请求执行终端命令或 MCP 工具时会弹出授权框（显示工具、参数、撤销成本）。「允许」继续，「拒绝」会回填拒绝原因给模型。5 分钟未选择视为拒绝。

## 进阶教程

### 添加技能

技能是预置的指令集，放对位置即可被自动加载并注入系统提示词：

```
<App 私有目录>/skills/<技能名>/SKILL.md
```

`SKILL.md` 格式（frontmatter + 正文）：

```markdown
---
name: code-review
description: 审查代码改动
---
步骤：
1. 读 diff
2. 给结论
```

- `name` 必填，`description` 可选；无法解析或缺少 `SKILL.md` 的目录会被跳过。

### 配置 MCP 服务器

「设置 → MCP」添加服务器（仅支持 stdio 类型）：

- `name`：服务器名（不含 `__`）
- `command` + `args`：启动命令（如 `npx`、`-y`、`some-mcp-server`）

启用后其工具以 `mcp__<server>__<tool>` 暴露给模型；不可用时 Agent 会明确提示并退回内置工具。

### 记忆使用

模型会自动读取记忆清单（description），需要正文时调用 `memory_read`；你可以在对话中直接要求 Agent「记住……」，它会调用 `memory_save`。

### 工作区

工作区是 App 私有目录下的 `workspace/`（右上角文件夹按钮可查看根路径与顶层文件列表）。Agent 可以直接在其中创建、读写、编辑、删除文件来开发软件：

- 告诉它「在 workspace 里创建一个 Flutter 项目」「写一个 `main.py` 并运行它」
- 文件工具：`file_list` / `file_read` / `file_write` / `file_edit` / `file_delete`
- BUILD 模式下写/编辑/删除需确认；PLAN 模式只读；AUTO 模式免确认
- 文件读取有 2000 行 / 200KB 窗口，大文件会提示用 `start_line` 分段续读

### 构建与验证（开发者）

```bash
# Rust Core（本地可完整验证）
cargo test -p yaya-core
cargo check -p yaya-core --features cloud-http

# Android target 编译检查（无需 NDK）
cargo check -p yaya-core-jni --target aarch64-linux-android --no-default-features

# 交叉编译 JNI 库 + 构建 APK（需 NDK 与 Flutter）
ANDROID_NDK_HOME=<ndk> bash scripts/build-android.sh
flutter build apk --release
```

- 端侧推理默认 STUB（不可用），需显式开启 llama.cpp 全量构建。
- 容器内（aarch64）无法本地链接 .so（NDK 仅提供 x86_64 工具链），交叉链接与云端后端验证在 CI 完成。

### 数据库迁移

数据库版本由 `AgentDatabase` 的 `DB_VERSION` 管理（当前 2）。新增表/列时：递增版本 → 在 `onUpgrade` 补逐级迁移分支 → 同步 `onCreate`。已有用户库升级会自动执行迁移。
