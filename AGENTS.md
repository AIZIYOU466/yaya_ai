# YAYai 项目规则

本文件定义项目架构规则，**未经用户同意不得修改**。偏离本文件的改动必须先征得用户确认并同步更新本文件。

**定位**：运行在 Android 设备上的**聊天与开发者助手 Agent** —— AI 对话、内置 proot 终端、MCP 工具。**不操控设备**（无无障碍/屏幕操作/系统手势）。**单端工程**（不含桌面端，无 gRPC）。

## R1 工作硬性要求（用户明示，最高优先级）

用户明示的硬性工作态度与流程要求，**任何改动前必读并逐条遵守**；优先级等同本文件其它规则，高于本 AI 的默认习惯。偏离本 R1 的改动必须先征得用户确认。

### R1.1 态度底线

- **绝对严谨、绝对正确**：以正向约束要求自身，**绝对禁止懒惰和懈怠**对待工作。
- **绝对真实准确且广泛**：工作必须建立在**真实、准确、广泛**的资料之上加以验证，不得凭印象/记忆/推测下结论。
- **数据规律 + 主观能动性**：尊重数据规律，并与自身**充分且正向有力的主观能动性与创造性**结合——证据链完整后主动设计方案，而非等待指令。
- **绝对准确、绝对高效、绝对认真**是最基本要求，不是加分项。

### R1.2 改动前的强制流程（根因先行）

1. **先找根因**：所有修改代码都要**事先找到绝对真实正确的根因**，合理分析。
2. **根因 → 方案转化**：把"问题真正所在"转化成"真正正确的方案"再实施，不允许对着症状打补丁。
3. **一切从优**：坚持**一切从优、一切从高质量最佳方案**着手，默认目标是最优解而非"能跑就行"。
4. 找不到根因不许改；改错了回到第 1 步重新找根因，不叠加补丁。

### R1.3 创新与权衡

- 学会在**广泛验证被认可的方法**上加以**合理的创新**：先确认业界/上游/官方推荐做法，再在其上改进。
- **既看到好处，又看到弊端**：任何方案同时列出优点、缺点、风险、成本。
- **学会权衡利弊**：给出选择该方案而非其它候选的显式理由。
- 不能只呈现"新方案好在哪"，必须也回答"新方案哪里可能翻车"。

### R1.4 局部最优与全局最优

- **各工人（模块/子任务）之间须存在紧密合乎逻辑的联系**：不是孤岛，互相配合。
- 既追求**各功能最优**，也追求**整体全局最优**。
- **以优异的各部分构成优异的整体，以整体彰显软件的优秀**：整体体验 > 单点炫技。
- **体现新事物的发展，随社会变化而变化**：架构与方案要能演进，避免一次性锁死。

### R1.5 外部条件敏感项（不可擅作主张）

- AI 供应商的**具体模型、价格变动**（如 token 单价、上下文/合计费用计算）必须**紧跟官方变化**，注重条件对软件优化的作用，**不可擅作主张**。
- 涉及这类外部条件时：查官方最新公告/文档，不允许凭历史记忆或直接沿用旧值。
- 遇到"官方变了但代码没跟上"：优先同步官方口径，再做优化叠加。

### R1.6 较大改动必须先询问

- **较大改动**必须**积极向用户询问，提供方案建议**，不能自作主张落地。
- 判断口径（可参照但不限于）：影响架构分层或公共契约（trait/interface/schema）；影响持久化/协议/数据格式；影响外部依赖/供应商接入方式；影响 UI 视觉/交互大改；影响多文件（≥3）或跨模块。
- 做法：进入 PLAN 模式或用 askUserQuestion 给出 2~3 个候选方案 + 各自优劣 + 推荐，用户确认后再动手。

### R1.7 与既有约定的关系

- 与其它规则（R2-R19）及项目记忆既有约定不冲突，一起执行。
- 与本 R1 冲突时以本 R1 为准；边界不清先问用户。

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

**唯一实现**：`core/src/agent/router.rs`。Android 经 JNI 调用，**禁止**在 Kotlin/Compose 侧重复实现路由。

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
| Compose UI ↔ Kotlin 执行层 | 进程内直接调用（AgentApi 接口） | `ui/` ↔ `MainActivity.kt` |
| Compose Git UI ↔ Kotlin GitHost ↔ proot 容器 | 进程内直接调用 + stdin/stdout | `GitHost.kt` ↔ `ProotManager.kt` |
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
  - `core/src/agent/degrade.rs` / `signals.rs` —— 降级状态机（健康环）/ 环境信号槽
  - `core/src/agent/recorder.rs` —— 事件录制与回放（`RecordingBackend` / `ReplayBackend`，ROADMAP 任务 38）
  - `core/src/agent/workspace.rs` —— 工作区文件系统（`FileAccess` trait + 6 个文件工具：list / read / patch / write / edit / delete）
- **Android 经 JNI 共享 core**：`jni/` crate 编译为 `libyaya_core_jni.so`；平台差异经 `ActionExecutor` / `ModelBackend` / `McpClient` 三个 trait 由 Kotlin 实现注入。
- **禁止**在 Kotlin/Compose 侧重复实现循环、路由、工具层或 OpenAI 解析（防逻辑漂移）。

## R8 CI 默认 STUB

- Rust job（`.github/workflows/rust-core.yml`）：`cargo test -p yaya-core` 与 `cargo check --workspace`，默认**不**启用 `cloud-http`。
- Android job（`.github/workflows/android-release.yml`）：先用 NDK + rust android targets + `scripts/build-android.sh` 交叉编译 `libyaya_core_jni.so`，再 `./gradlew assembleRelease`；默认 **STUB 推理**（不启用 `-PenableLlamaCpp`）。
- 完整 llama.cpp 构建**仅在本地进行**（`-PenableLlamaCpp=true` 且源码就位），CI 中跳过。

## R10 MCP 工具接入

- **MCP 工具并入统一工具层**（R6）：模型可见名为 `mcp__<server>__<tool>`，与内置工具共用 `tools::specs()` / `dispatch()` 路径；`core/src/agent/mcp.rs` 是命名空间与 `McpClient` trait 的唯一规范源。
- **进程与 JSON-RPC 属平台能力**：Android 由 `McpProcessManager.kt` 实现（stdio 子进程 + `initialize` 握手 + 按 JSON-RPC `id` 匹配读取 + 超时），经 JNI 由 `AgentHost.mcpListTools` / `mcpCallTool` 暴露；**禁止**在 Kotlin/Compose 侧表达工具语义或命名空间。
- **传输仅 stdio**：Streamable HTTP 未实现，配置中 `type != "stdio"` 的服务器被跳过并记录告警。
- 服务器名与工具名**不得包含 `__`**（否则无法无歧义解析，该工具不暴露给模型）。
- 配置来源：SharedPreferences 键 `mcp_servers`（JSON 数组），随任务经 `configJson.mcpServers` 下发；无启用项时不注册 `McpClient`，工具集与未接入时**完全一致**。
- MCP 不可用（列工具/调用失败）必须显式回填错误文本或发 `Event::Notice`，**禁止**静默跳过或伪造成功。

## R12 运行模式与工具授权（唯一规范源 `core/src/agent/permission.rs`）

- 运行模式：`BUILD`（按撤销成本确认）/ `PLAN`（只读，拦截写操作）/ `AUTO`（免授权）。模式经 config `mode` 下发（`build` / `plan` / `auto`）。
- 撤销成本分级：可逆（`notify` / `clipboard_read`）直接放行；半可逆（`clipboard_write`）直接放行；不可逆（`terminal_exec` 与全部 MCP 工具）在 BUILD 下需用户确认。
- 需确认时经 `Approver` trait 回调平台弹窗（Android：`AgentHost.requestApproval` 跨线程等待 UI 选择）；未注册 `Approver` 按拒绝处理（安全默认）。
- 每次工具调用的策略判定经 `Event::ToolPolicy` 上报（决策原因码），供追溯；拒绝时回填明确原因给模型，**禁止静默跳过**。
- **禁止**在 Kotlin/Compose 侧重复实现策略判定。

## R13 本地持久化（Android 侧 SQLite，零依赖）

- 会话 / 消息 / 检查点由 `android/.../AgentDatabase.kt`（SQLiteOpenHelper）持久化；Rust Core 不直接落盘，但暴露可序列化状态交平台保存（任务 43）。
- **循环光标（任务 43，2026-10-18 R13 更新，已获用户授权）**：core 暴露可序列化 `LoopState`（`stage` + `step` + `messages` + 待执行 tool_calls 游标 `pending_calls`/`pending_cursor` + degrade 快照），经 `Event::LoopState` 交平台落盘（`AgentHost` 存 `agent_state/loop_state`），恢复经 `RunConfig.loop_state` 注入续跑；检查点从「消息快照」升级为「循环光标」。存档时机：工具调用执行后（每个 tool 完成发一档）、后台中断前（stage=generate）。恢复语义：stage=tools 时从 `pending_cursor` 续跑未执行部分（已完成工具不重复执行），stage=generate 时重跑本步生成；任务 `done` 时平台清除断点，降级快照保留。序列化边界含 4C-2/4C-3 最终形态（`BehaviorHistory`：`forced_failure_count` / `canary_cooling` / `last_tool_call` 等）。
- `AgentHost.onEvent` 同步写库（tool 消息、流式 token 累积后的 assistant 文本、system 提示）；`run()` 开始写入 user 消息并 upsert 会话，并注入 `degradeState` / `loopState`。
- 每次工具调用执行前自动保存一次检查点（消息快照），UI 可回滚到任意检查点并继续（保留；循环光标是续跑机制，检查点仍是回滚 UI）。
- 进程被杀后重启：UI 经 `AgentApi.loadRecentSession` 恢复最近会话展示；任务续跑由 `loop_state` 断点恢复。
- 表结构：`sessions` / `messages` / `checkpoints` / `memories`；版本管理用 `DB_VERSION` + `onUpgrade` 逐级迁移（见 `AgentDatabase`）。

## R14 技能 / 记忆 / 子代理

- **技能**：`filesDir/skills/<name>/SKILL.md`（frontmatter：`name` / `description` + 正文）；`RunConfig.skills_dir` 指向该目录，core 扫描并把正文注入系统提示词。
- **记忆**：`memories` 表；工具 `memory_list` / `memory_read` / `memory_save` / `memory_edit` / `memory_delete`（唯一实现在 `core/src/agent/memory.rs`）；description 清单注入系统提示词，正文经工具按需读取。
- **子代理**：`subagent` 工具（唯一实现在 `core/src/agent/subagent.rs`）以独立上下文运行子循环，子任务受同一运行模式策略约束；PLAN 模式拦截 `subagent`。
  - **并行（任务 32）**：同一轮的多个 `subagent` 调用经 `run_subagents_parallel` 并发执行，上限取 `RunConfig.max_parallel_subagents`（1–8，Android 设置页暴露 1–5）；平台经 `SubagentFork` 工厂为每个子代理派生独立 `AgentCore`（JNI 侧重建同一套后端与平台 trait），子线程事件先缓冲、按调用下标重放（`Event::Subagent` 标记 `id`/`agent`/`phase`）。平台未注入工厂或上限为 1 时回退顺序递归。
  - **自定义定义**：`RunConfig.subagents`（`SubagentDef`：说明 / 专属指令 / 专属模型 / 工具白名单 / 步数）；内置 `explore` 只读代理（白名单固定为文件列/读、记忆列/读、剪贴板读），平台只可启停、不可放宽其白名单。
  - **工具白名单**：`RunConfig.tool_allowlist` 收窄当前代理可见工具，未列入的工具不暴露且调用被拒绝；子代理白名单与父级取交集（只收窄不放宽）；子代理内部不再并行、不再派生自定义代理。
  - **并行环境约束**：proot 容器与授权弹窗为单实例，平台侧必须串行化授权请求（`AgentHost.requestApproval` 已加队列锁），容器命令由 `ProotManager` 同步串行执行。
- **禁止**在 Kotlin/Compose 侧重复实现技能解析、记忆工具或子代理循环。

## R15 决策原则（客观判据优先）

改动与新增功能遵循以下客观判据，不拍脑袋：

- **可逆性**：操作可逆/可撤销 → 可直接执行；半可逆 → 谨慎并提示；不可逆 → 必须经授权确认（见 R12）。
- **唯一规范源**：循环 / 路由 / 工具层 / 协议解析逻辑唯一实现在 `core/`；不得重复实现（防逻辑漂移）。
- **可验证性**：core 改动必须带 `cargo test` 可复现的测试；无法在容器内验证的路径（Android UI、交叉链接、云端 HTTP）必须标注 UNVERIFIED，**不得宣称已验证**。
- **文档同步**：功能/工具/行为变化 → 同步 `README.md` 与 `docs/`；规则变化 → 同步本文件。
- **最少工具**：新能力优先复用现有工具与平台 trait，不新增重复抽象。

## R16 变更流程（开发者 / AI 助手通用）

1. **先 core 后平台**：新能力先在 `core/` 实现（含测试）→ JNI 桥 → Kotlin 执行层 → Compose UI。
2. **每步验证**：core 改完即跑 `cargo test -p yaya-core`；JNI 改完跑 `cargo check -p yaya-core-jni --target aarch64-linux-android --no-default-features`。
3. **事件协议优先**：跨端交互一律经 `Event` 事件（`events.rs`），新增事件类型 Compose 侧 `when` 可安全忽略（无 else 分支）。
4. **权限与安全**：新增工具必须先定义撤销成本（`permission.rs`）并评估注入风险（R15 可逆性）；默认拒绝、显式放行。
5. **不静默**：失败必须显式报错或发 `Event::Notice`，禁止静默假数据、静默空回复、静默跳过。
6. **文档同步**：按 R15 文档同步原则更新 `README.md` / `docs/` / 本文件。

## R17 发版流程

版本由 Git Tag 驱动（`vX.Y.Z`），CI（`.github/workflows/android-release.yml`）捕获 Tag 构建签名 APK：

1. `main` 上所有待发布改动已合入且 `cargo test` / JNI check 通过。
2. 打 `vX.Y.Z` Tag 并推送 → CI 构建 Release APK。
3. **真机验证三条主线**：AI 对话 + 终端容器 + MCP（或端侧模型），通过后才算发布完成。
4. 有回归 → 修 `main` → 重新打 Tag（递增 patch）；不重写已推送 Tag。
5. 数据库迁移（`AgentDatabase.DB_VERSION` 递增）随发版冻结，已发布版本号不可复用。

## R18 能力探测 / 金丝雀 / 环境验证

- **能力探测**：能力由 `RunConfig.capabilities`（config `capabilities` 对象）声明；core 据此钳制 `max_tokens` 不超过上下文窗口一半，推导 `max_messages` 压缩阈值（窗口越小越激进）。真实 HTTP 探测由平台/Kotlin 完成（core 只做能力模型的解析与应用）。
- **金丝雀**：`RunConfig.canary: true` 时在任务开始前运行一组已知答案的探测题（默认：简单算术），异常经 `Event::Notice` 上报（不阻断任务，成本可忽略）。
- **环境验证**（`verifier.rs`）：对可验证工具（如 `clipboard_write`）执行后自动读回比较；验证失败回填原因给模型并标记为失败；其余工具视执行成功为完成（命令已返回输出、通知已发送）。不假设工具成功（客观信号优先）。

## R19 工作区文件系统

- 工作区根：`filesDir/workspace/`（Kotlin `WorkspaceFileAccess`），工具只接受**相对路径**；所有路径先 `normalize` 再校验必须落在工作区内（防目录穿越），绝对路径与越界路径一律拒绝。
- **容器一致性**：工作区同时由 `ProotManager` bind 进 proot 容器固定路径 `/workspace`，使 `git`/`terminal` 工具与 `file_*` 工具看到同一目录（ROADMAP 任务 23）。
- 工具：`file_list` / `file_read` / `file_patch` / `file_write` / `file_edit` / `file_delete`（唯一实现在 `core/src/agent/workspace.rs`）；读取有 2000 行 / 200KB 窗口，超限截断并用 `start_line` 分段续读。
- 权限：读/列只读放行（PLAN 可用）；写/编辑/删除不可逆（BUILD 需确认，见 R12）。
- **禁止**在 Kotlin/Compose 侧重复实现工具语义与参数校验（只做平台文件操作与路径安全）。

## 构建与验证

- Core：`cargo test -p yaya-core`；云端后端：`cargo check -p yaya-core --features cloud-http`。
- Android target 编译检查（无需 NDK，check 不链接）：`cargo check -p yaya-core-jni --target aarch64-linux-android --no-default-features`。
- Android JNI 交叉编译：`ANDROID_NDK_HOME=<ndk> bash scripts/build-android.sh`（直接用 NDK clang → `android/app/src/main/jniLibs/`，不依赖 cargo-ndk）。
- Android App：`./gradlew assembleRelease`。CI：`.github/workflows/android-release.yml`
  （JDK 17 / Gradle 8.7 wrapper 已入库，AGP 8.3.2 / Kotlin 1.9.24 / Compose BOM 2024.05）。
- 本地工具链不全时，以 CI 结果为准；标记 UNVERIFIED 的路径不得宣称已验证。
  - **已知（已突破 2026-10）**：本容器为 aarch64，NDK 仅提供 linux-x86_64 预编译工具链、二进制无法直接运行；但 NDK sysroot 是跨架构数据，配合系统 clang（apt install clang lld）即可本地交叉链接：
    `scripts/build-android.sh` 已双模式化（aarch64 主机自动切系统 clang + NDK sysroot，含 crt/libunwind stub 修复），三个 ABI 均本地可产出 `.so` 与完整 APK（`./gradlew assembleDebug`，STUB 模式跳过 CMake——ModelBridge 负载优雅降级）。
    全量 llama.cpp（`-PenableLlamaCpp=true`）仍需 NDK/cmake 环境（CI 验证）。`cloud-http`（ring）本地产出已验证，但对接真实云端的运行时行为仍需真机/CI 验证。
