# YAYai 项目规则

本文件定义项目架构规则，**未经用户同意不得修改**。偏离本文件的改动必须先征得用户确认并同步更新本文件。

**定位**：运行在 Android 设备上的 AI 驱动代码编辑器 —— 内置终端、AI Agent、MCP 协议。**单端工程**（不含桌面端，无 gRPC）。

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
  - `core/src/agent/observer.rs` / `executor.rs` / `model.rs` —— 三个平台 trait
  - `core/src/agent/tools.rs` / `openai.rs` / `events.rs` / `cloud.rs` / `mcp.rs`
- **Android 经 JNI 共享 core**：`jni/` crate 编译为 `libyaya_core_jni.so`；平台差异经 `ScreenObserver` / `ActionExecutor` / `ModelBackend` / `McpClient` 四个 trait 由 Kotlin 实现注入。
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

## 构建与验证

- Core：`cargo test -p yaya-core`；云端后端：`cargo check -p yaya-core --features cloud-http`。
- Android target 编译检查（无需 NDK，check 不链接）：`cargo check -p yaya-core-jni --target aarch64-linux-android --no-default-features`。
- Android JNI 交叉编译：`ANDROID_NDK_HOME=<ndk> bash scripts/build-android.sh`（直接用 NDK clang → `android/app/src/main/jniLibs/`，不依赖 cargo-ndk）。
- Android App：`flutter build apk --release`。CI：`.github/workflows/flutter-android.yml`
  （Flutter 3.22.2 / JDK 17 / Gradle 8.7 wrapper 已入库）。
- 本地工具链不全时，以 CI 结果为准；标记 UNVERIFIED 的路径不得宣称已验证。
  - **已知**：本容器为 aarch64，而 NDK 仅提供 linux-x86_64 预编译工具链且无 qemu/binfmt，**本地无法链接 `.so`**；交叉链接与 `cloud-http`（ring）只能在 x86_64 CI 上验证。
