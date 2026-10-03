# YAYai 项目规则

本文件定义项目架构规则，**未经用户同意不得修改**。偏离本文件的改动必须先征得用户确认并同步更新本文件。

**定位**：运行在 Android 设备上的**聊天与开发者助手 Agent** —— AI 对话、内置 proot 终端、MCP 工具。**不操控设备**（无无障碍/屏幕操作/系统手势）。**单端工程**（不含桌面端，无 gRPC）。

## R2 三层模型路由（Model Router 默认策略，唯一规范源 `core/src/agent/router.rs`）

优先级顺序：

1. 用户强制指定（force != auto）→ 直接用指定后端
2. 简单提示词 && 端侧非 STUB && 非低延迟模式 → JNI（端侧 2B-4B）
3. 桌面可达 && （中/高复杂度）→ 桌面 gRPC（7B-70B）
4. 桌面可达 && 端侧 STUB → 桌面 gRPC
5. 云端已配置 && 有网络 → 云端
6. 端侧非 STUB → JNI 兜底
7. 全部不可用 → 返回明确错误（**禁止静默假数据、静默空回复**）

复杂度判定：含 ` ``` ` 代码块或长度 > 1024 → Hard；长度 ≤ 256 → Simple；其余 Medium。

规则 3/4 的「桌面」为保留分支：单端工程下无桌面后端注册，`desktop_ok` 恒为 false，自动跳过；保留以便未来接入。

**唯一实现**：`core/src/agent/router.rs`。Android 经 JNI 调用，**禁止**在 Kotlin/Dart 侧重复实现路由。

## R4 llama.cpp 构建开关（默认 STUB）

| 端 | 默认（STUB） | 全量推理 |
|---|---|---|
| Android | `./gradlew assembleDebug` | `./gradlew assembleDebug -PenableLlamaCpp=true` |

- Android 全量需把 llama.cpp 源码放到 `android/app/src/main/cpp/llama_cpp/`（已被 `.gitignore` 忽略）；缺源码时 CMake **报错终止**，不静默降级。
- STUB 必须在运行时显式输出 `[STUB] 当前为桩实现，推理结果不可用`，禁止伪装成成功推理。
- **版本登记**（JNI 所针对的 llama.cpp API 版本）：
  - 目标窗口：b4100 前后（`llama_sampler_*` 与 `llama_new_context_with_model` 并存、model 版 `llama_tokenize` / `llama_token_to_piece`）
  - 状态：**UNVERIFIED** —— 当前容器无 llama.cpp 源码，全量路径尚未编译验证；首次通过编译后在此登记确认的 tag。

## R5 通信协议分层

| 链路 | 协议 | 实现位置 |
|---|---|---|
| Dart UI ↔ Kotlin 执行层 | Platform Channel（进程内） | `lib/platform/agent_channel.dart` ↔ `MainActivity.kt` |
| Kotlin ↔ Rust Core | JNI | `android/.../AgentHost.kt` ↔ `jni/src/lib.rs` |
| Kotlin ↔ C/C++（llama） | JNI | `ModelBridge.kt` ↔ `llama_jni.cpp` |
| Rust Core ↔ 云端 | HTTP/2 + SSE | `core/src/agent/cloud.rs`（feature `cloud-http`） |
| Kotlin ↔ MCP 服务器 | stdio JSON-RPC（子进程） | `android/.../McpProcessManager.kt` |

## R6 Agent Core 规范

- **循环机、任务状态机、模型路由、工具层、OpenAI 协议解析的唯一规范源：`core/`（Rust crate `yaya-core`）**；`cargo test -p yaya-core` 为可执行规范。
- 模块划分：
  - `core/src/agent/run.rs` —— ReAct 循环机
  - `core/src/agent/state.rs` —— 任务状态机
  - `core/src/agent/router.rs` —— Model Router
  - `core/src/agent/permission.rs` —— 运行模式与授权策略（撤销成本分级）
  - `core/src/agent/skill.rs` / `memory.rs` / `subagent.rs` —— 技能 / 自动记忆 / 子代理
  - `core/src/agent/executor.rs` / `model.rs` —— 平台 trait（`ActionExecutor` / `ModelBackend`）；`mcp.rs` 定义 `McpClient`
  - 内置工具：`terminal_exec` / `clipboard_read` / `clipboard_write` / `notify`（`tools.rs`）；设备操控类工具已移除
  - `core/src/agent/tools.rs` / `openai.rs` / `events.rs` / `cloud.rs` / `mcp.rs`
  - `core/src/agent/capability.rs` / `canary.rs` / `verifier.rs` —— 能力探测 / 金丝雀 / 环境验证
- **Android 经 JNI 共享 core**：`jni/` crate 编译为 `libyaya_core_jni.so`；平台差异经 `ActionExecutor` / `ModelBackend` / `McpClient` 三个 trait 由 Kotlin 实现注入。
- **禁止**在 Kotlin/Dart 侧重复实现循环、路由、工具层或 OpenAI 解析（防逻辑漂移）。

## R8 CI 默认 STUB

- Rust job（`.github/workflows/rust-core.yml`）：`cargo test -p yaya-core` 与 `cargo check --workspace`，默认**不**启用 `cloud-http`。
- Android job（`.github/workflows/flutter-android.yml`）：先用 NDK + rust android targets + `scripts/build-android.sh` 交叉编译 `libyaya_core_jni.so`，再 `flutter build apk`；默认 **STUB 推理**（不启用 `-PenableLlamaCpp`）。
- 完整 llama.cpp 构建**仅在本地进行**（`-PenableLlamaCpp=true` 且源码就位），CI 中跳过。

## R10 MCP 工具接入

- **MCP 工具并入统一工具层**（R6）：模型可见名为 `mcp__<server>__<tool>`，与内置工具共用 `tools::specs()` / `dispatch()` 路径；`core/src/agent/mcp.rs` 是命名空间与 `McpClient` trait 的唯一规范源。
- **进程与 JSON-RPC 属平台能力**：Android 由 `McpProcessManager.kt` 实现（stdio 子进程 + `initialize` 握手 + 按 JSON-RPC `id` 匹配读取 + 超时），经 JNI 由 `AgentHost.mcpListTools` / `mcpCallTool` 暴露；**禁止**在 Kotlin/Dart 侧表达工具语义或命名空间。
- **传输仅 stdio**：Streamable HTTP 未实现，配置中 `type != "stdio"` 的服务器被跳过并记录告警。
- 服务器名与工具名**不得包含 `__`**（否则无法无歧义解析，该工具不暴露给模型）。
- 配置来源：SharedPreferences 键 `mcp_servers`（JSON 数组），随任务经 `configJson.mcpServers` 下发；无启用项时不注册 `McpClient`，工具集与未接入时**完全一致**。
- MCP 不可用（列工具/调用失败）必须显式回填错误文本或发 `Event::Notice`，**禁止**静默跳过或伪造成功。

## R12 运行模式与工具授权（唯一规范源 `core/src/agent/permission.rs`）

- 运行模式：`BUILD`（按撤销成本确认）/ `PLAN`（只读，拦截写操作）/ `AUTO`（免授权）。模式经 config `mode` 下发（`build` / `plan` / `auto`）。
- 撤销成本分级：可逆（`notify` / `clipboard_read`）直接放行；半可逆（`clipboard_write`）直接放行；不可逆（`terminal_exec` 与全部 MCP 工具）在 BUILD 下需用户确认。
- 需确认时经 `Approver` trait 回调平台弹窗（Android：`AgentHost.requestApproval` 跨线程等待 UI 选择）；未注册 `Approver` 按拒绝处理（安全默认）。
- 每次工具调用的策略判定经 `Event::ToolPolicy` 上报（决策原因码），供追溯；拒绝时回填明确原因给模型，**禁止静默跳过**。
- **禁止**在 Kotlin/Dart 侧重复实现策略判定。

## R13 本地持久化（Android 侧 SQLite，零依赖）

- 会话 / 消息 / 检查点由 `android/.../AgentDatabase.kt`（SQLiteOpenHelper）持久化；Rust Core 不持久化（无状态循环机）。
- `AgentHost.onEvent` 同步写库（tool 消息、流式 token 累积后的 assistant 文本、system 提示）；`run()` 开始写入 user 消息并 upsert 会话。
- 每次工具调用执行前自动保存一次检查点（消息快照），UI 可回滚到任意检查点并继续。
- 进程被杀后重启，Dart 经 `loadRecentSession` 恢复最近会话展示。
- 表结构：`sessions` / `messages` / `checkpoints` / `memories`；版本管理用 `DB_VERSION` + `onUpgrade` 逐级迁移（见 `AgentDatabase`）。

## R14 技能 / 记忆 / 子代理

- **技能**：`filesDir/skills/<name>/SKILL.md`（frontmatter：`name` / `description` + 正文）；`RunConfig.skills_dir` 指向该目录，core 扫描并把正文注入系统提示词。
- **记忆**：`memories` 表；工具 `memory_list` / `memory_read` / `memory_save` / `memory_edit` / `memory_delete`（唯一实现在 `core/src/agent/memory.rs`）；description 清单注入系统提示词，正文经工具按需读取。
- **子代理**：`subagent` 工具（唯一实现在 `core/src/agent/subagent.rs`）以顺序递归方式运行子循环（独立上下文，复用同一平台能力），子任务受同一运行模式策略约束；PLAN 模式拦截 `subagent`。
- **禁止**在 Kotlin/Dart 侧重复实现技能解析、记忆工具或子代理循环。

## R15 决策原则（客观判据优先）

改动与新增功能遵循以下客观判据，不拍脑袋：

- **可逆性**：操作可逆/可撤销 → 可直接执行；半可逆 → 谨慎并提示；不可逆 → 必须经授权确认（见 R12）。
- **唯一规范源**：循环 / 路由 / 工具层 / 协议解析逻辑唯一实现在 `core/`；Kotlin/Dart 不得重复实现（防逻辑漂移）。
- **可验证性**：core 改动必须带 `cargo test` 可复现的测试；无法在容器内验证的路径（Android UI、交叉链接、云端 HTTP）必须标注 UNVERIFIED，**不得宣称已验证**。
- **文档同步**：功能/工具/行为变化 → 同步 `README.md` 与 `docs/`；规则变化 → 同步本文件。
- **最少工具**：新能力优先复用现有工具与平台 trait，不新增重复抽象。

## R16 变更流程（开发者 / AI 助手通用）

1. **先 core 后平台**：新能力先在 `core/` 实现（含测试）→ JNI 桥 → Kotlin 执行层 → Dart UI。
2. **每步验证**：core 改完即跑 `cargo test -p yaya-core`；JNI 改完跑 `cargo check -p yaya-core-jni --target aarch64-linux-android --no-default-features`。
3. **事件协议优先**：跨端交互一律经 `Event` 事件（`events.rs`），新增事件类型 Dart 侧 `switch` 可安全忽略（无 default 分支）。
4. **权限与安全**：新增工具必须先定义撤销成本（`permission.rs`）并评估注入风险（R15 可逆性）；默认拒绝、显式放行。
5. **不静默**：失败必须显式报错或发 `Event::Notice`，禁止静默假数据、静默空回复、静默跳过。
6. **文档同步**：按 R15 文档同步原则更新 `README.md` / `docs/` / 本文件。

## R17 发版流程

版本由 Git Tag 驱动（`vX.Y.Z`），CI（`.github/workflows/flutter-android.yml`）捕获 Tag 构建签名 APK：

1. `main` 上所有待发布改动已合入且 `cargo test` / JNI check 通过。
2. 打 `vX.Y.Z` Tag 并推送 → CI 构建 Release APK。
3. **真机验证三条主线**：AI 对话 + 终端容器 + MCP（或端侧模型），通过后才算发布完成。
4. 有回归 → 修 `main` → 重新打 Tag（递增 patch）；不重写已推送 Tag。
5. 数据库迁移（`AgentDatabase.DB_VERSION` 递增）随发版冻结，已发布版本号不可复用。

## R18 能力探测 / 金丝雀 / 环境验证

- **能力探测**：能力由 `RunConfig.capabilities`（config `capabilities` 对象）声明；core 据此钳制 `max_tokens` 不超过上下文窗口一半，推导 `max_messages` 压缩阈值（窗口越小越激进）。真实 HTTP 探测由平台/Kotlin 完成（core 只做能力模型的解析与应用）。
- **金丝雀**：`RunConfig.canary: true` 时在任务开始前运行一组已知答案的探测题（默认：简单算术），异常经 `Event::Notice` 上报（不阻断任务，成本可忽略）。
- **环境验证**（`verifier.rs`）：对可验证工具（如 `clipboard_write`）执行后自动读回比较；验证失败回填原因给模型并标记为失败；其余工具视执行成功为完成（命令已返回输出、通知已发送）。不假设工具成功（客观信号优先）。

## 构建与验证

- Core：`cargo test -p yaya-core`；云端后端：`cargo check -p yaya-core --features cloud-http`。
- Android target 编译检查（无需 NDK，check 不链接）：`cargo check -p yaya-core-jni --target aarch64-linux-android --no-default-features`。
- Android JNI 交叉编译：`ANDROID_NDK_HOME=<ndk> bash scripts/build-android.sh`（直接用 NDK clang → `android/app/src/main/jniLibs/`，不依赖 cargo-ndk）。
- Android App：`flutter build apk --release`。CI：`.github/workflows/flutter-android.yml`
  （Flutter 3.22.2 / JDK 17 / Gradle 8.7 wrapper 已入库）。
- 本地工具链不全时，以 CI 结果为准；标记 UNVERIFIED 的路径不得宣称已验证。
  - **已知**：本容器为 aarch64，而 NDK 仅提供 linux-x86_64 预编译工具链且无 qemu/binfmt，**本地无法链接 `.so`**；交叉链接与 `cloud-http`（ring）只能在 x86_64 CI 上验证。
