# YAYai — Android AI Agent 客户端

运行在 Android 设备上的**聊天与开发者助手 Agent**：AI 对话、内置 proot Linux 终端、
工作区文件工具、MCP 工具、技能/记忆/子代理。目标是让模型在移动端真正发挥全部能力
（自主多步、工具调用、可中断、可观测、失败不静默）。

> 本仓库为**单端工程**（仅 Android）。**不操控设备**：无无障碍服务、无屏幕/手势操作、
> 无系统级自动化（见 `AGENTS.md`）。详细使用说明见 [`docs/README.md`](docs/README.md)。
> 架构规则见 [`AGENTS.md`](AGENTS.md)，演进计划见 [`ROADMAP.md`](ROADMAP.md)。

## 架构

```
Flutter UI (Dart)
  │  Platform Channel
  ▼
Kotlin 执行层
  ├─ 剪贴板 / 本地通知（不依赖无障碍服务）
  ├─ proot Linux 容器（RootfsInstaller 镜像目录 + ProotManager，工作区 bind 到 /workspace）
  ├─ McpProcessManager（stdio JSON-RPC 子进程 + initialize 握手 + 按 id 匹配 + 超时）
  ├─ AgentDatabase（SQLite：sessions / messages / checkpoints / memories）
  ├─ WorkspaceFileAccess（FileAccess trait + 路径归一化与越界校验）
  └─ llama.cpp（ModelBridge，默认 STUB）
  │  JNI
  ▼
Rust Agent Core（crate `yaya-core`，唯一规范源）
  ├─ run.rs        ReAct 循环机（观察→决策→流式生成→执行工具→回填）
  ├─ state.rs      任务状态机
  ├─ router.rs     三层模型路由（端侧 / 桌面[保留] / 云端）
  ├─ permission.rs 运行模式 BUILD/PLAN/AUTO + 撤销成本分级 + Approver 回调
  ├─ tools.rs      统一工具层（function-calling schema + 派发，含 MCP 命名空间）
  ├─ workspace.rs  工作区文件系统（FileAccess trait + 6 个文件工具）
  ├─ memory.rs     自动记忆（5 个工具 + description 清单注入）
  ├─ subagent.rs   子代理（顺序递归子循环，独立上下文）
  ├─ skill.rs      技能（SKILL.md 扫描与系统提示词注入）
  ├─ openai.rs     OpenAI 兼容协议（含流式 tool_calls 解析）
  ├─ cloud.rs      云端后端（reqwest + rustls，feature `cloud-http`）
  ├─ mcp.rs        MCP 工具接入（命名空间 + McpClient trait）
  ├─ capability.rs 能力模型解析与应用（上下文窗口钳制 token 预算）
  ├─ degrade.rs    降级状态机（Full / NoTools / BareText，五类失败信号 + 指数退避重探）
  ├─ canary.rs     金丝雀探测（已知答案题监控输出质量漂移）
  ├─ verifier.rs   环境验证 oracle（工具执行后读回比较，不假设成功）
  ├─ signals.rs    平台环境信号槽（网络丢失 / 切后台 / 省电，只传信号不传决策）
  ├─ local_parse.rs 端侧小模型 function-calling（ChatML/Hermes 渲染 + 文本解析）
  ├─ events.rs     跨端事件协议（含 ToolPolicy 决策原因码）
  └─ executor.rs / model.rs   平台 trait（ActionExecutor / ModelBackend / McpClient）
```

**归属原则**：循环机是编排者，属 Core（Rust，唯一实现）；Android 经 JNI 共享；
平台差异由 `ActionExecutor` / `ModelBackend` / `McpClient` 三个 trait 注入。
Kotlin/Dart 侧**不得**重复实现循环、路由、工具层或协议解析（防逻辑漂移）。

## 功能

### Agent 闭环

- **ReAct 多步循环** + OpenAI function-calling（含流式 `tool_calls`），支持最大步数与中断取消。
- **运行模式**：`BUILD`（按撤销成本确认）/ `PLAN`（只读，工具层硬拦截写操作）/ `AUTO`（免授权）。
  撤销成本分级：可逆（`notify`、只读操作）直接放行；半可逆（`clipboard_write`）放行；
  不可逆（`terminal_exec`、全部 MCP 工具、文件写/改/删）在 BUILD 下需用户确认。
- **失败不静默**：每次工具调用的策略判定经 `ToolPolicy` 上报决策原因码；失败必须显式
  报错或发 `Event::Notice`，禁止静默假数据、静默空回复、静默跳过。
- **检查点与回滚**：每次工具调用前自动保存消息快照，可回滚到任意检查点继续。
- **环境验证**：可验证工具（`clipboard_write`）执行后自动读回比较；验证失败回填原因并标记失败。

### 工具清单（16 个内置 + MCP 动态）

| 类别 | 工具 |
|---|---|
| 终端 | `terminal_exec`（proot Linux 容器，同步取回输出，带超时） |
| 剪贴板与通知 | `clipboard_read` / `clipboard_write` / `notify` |
| 工作区文件 | `file_list` / `file_read` / `file_patch` / `file_write` / `file_edit` / `file_delete` |
| 自动记忆 | `memory_list` / `memory_read` / `memory_save` / `memory_edit` / `memory_delete` |
| 子代理 | `subagent`（独立上下文递归子循环，受同一运行模式策略约束） |
| MCP | `mcp__<server>__<tool>`（stdio，并入统一工具层） |

工作区工具只接受**相对路径**，归一化后校验必须落在工作区内（防目录穿越），
绝对路径与越界路径一律拒绝。工作区同时 bind 进容器 `/workspace`，
使 git / terminal 与 `file_*` 工具看到同一目录。

### 运行时可靠性（模型网关不可信任时）

- **能力探测**：能力由 config 声明，Core 据此钳制 `max_tokens` 不超过上下文窗口一半，
  推导历史压缩阈值（窗口越小越激进）。
- **降级状态机**：五类失败信号各自阈值驱动 `Full → NoTools → BareText` 三档降级
  （`ToolProtocol` 3 次、`ToolSchema` 5 次、`FirstTokenTimeout` 2 次、`ChunkStall` 3 次、
  `Http5xx` 3 次仅标记低置信）。恢复靠 `2^round` 指数退避 + 金丝雀重探防抖动。
  降级模式是「端点池的零号档」——单端点也有 failover 目标。
  `capability`（端点能做什么，静态）与 `mode`（当前只用什么，动态）分离。
- **金丝雀**：任务开始前跑一组已知答案的探测题（默认简单算术），异常经 `Notice` 上报，
  不阻断任务、成本可忽略；省电模式下跳过。
- **环境信号**：Android 只负责感知环境并置位信号（网络断开 / 切后台 / 省电），
  是否降级、中止、跳过探测的决策全部留在 Core。

### 开发者工作台

- **文件浏览与代码编辑器**：缩进树形目录浏览工作区（长按新建/重命名/删除）；内置等宽
  编辑器（撤销/重做/保存、快捷符号栏、未保存退出确认），Markdown 渲染预览与代码语法
  高亮预览（纯 Dart 零依赖）。
- **Git 版本管理**：状态/分支/提交三标签页，经 proot 容器执行 git（需容器安装 git，
  如 Alpine `apk add git`；工作区已挂载进容器 `/workspace`）。
- **多会话管理**：新建、切换、重命名、删除；进程重启后恢复最近会话。
- **技能**：`filesDir/skills/<name>/SKILL.md`（frontmatter + 正文）自动扫描注入系统提示词。
- **Linux 容器**：镜像目录（内置 Alpine minirootfs 约 4MB + 用户自定义 URL/SHA256），
  支持安装/切换/重置。

### 端侧推理

llama.cpp JNI，**默认 STUB**（运行时显式输出桩提示，禁止伪装成成功推理）。
全量推理需 `-PenableLlamaCpp=true` 且源码就位；当前该路径**未编译验证**（见 AGENTS.md R4）。
`local_parse.rs` 为 2B-4B 端侧小模型自研 function-calling（不依赖原生 `tool_calls`）。

## 构建与验证

```bash
# 1. Rust Core（本地可完整验证，125 项测试）
cargo test -p yaya-core
cargo check -p yaya-core --features cloud-http

# 2. Android target 编译检查（无需 NDK，check 不链接）
cargo check -p yaya-core-jni --target aarch64-linux-android --no-default-features

# 3. 交叉编译 JNI 库 → android/app/src/main/jniLibs/
ANDROID_NDK_HOME=<ndk 路径> bash scripts/build-android.sh

# 4. 构建 APK（默认 STUB 推理）
flutter build apk --release
```

CI：`.github/workflows/rust-core.yml`（Core 测试）、`.github/workflows/flutter-android.yml`
（NDK + `scripts/build-android.sh` + APK）。

本容器为 aarch64，而 NDK 仅提供 linux-x86_64 预编译工具链且无 qemu/binfmt，
**本地无法链接 `.so`**；交叉链接与 `cloud-http`（ring）只能在 x86_64 CI 上验证。
标记 UNVERIFIED 的路径不宣称已验证。

## 配置

在「设置」中填入 OpenAI 兼容的 Base URL、API Key 与模型名即可使用云端后端。
MCP 服务器仅支持 stdio（`type != "stdio"` 的服务器被跳过并记录告警）。

## 许可证

GPL-3.0（见 [`LICENSE`](LICENSE)）
