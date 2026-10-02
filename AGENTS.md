# YAYai 项目规则

本文件定义项目架构规则，**未经用户同意不得修改**。偏离本文件的改动必须先征得用户确认并同步更新本文件。

## R1 桌面自动化协议：gRPC 为核心

- 桌面 Agent 服务 = tonic gRPC，监听 `0.0.0.0:50051`，契约唯一来源 `proto/agent.proto`。
- HTTP 仅保留两个端点（`0.0.0.0:8082`）：`GET /health`、`GET /debug`。
- 禁止新增承载核心协议的 HTTP 端点（原 `/v1/chat/completions`、`/agent/observe`、`/agent/execute` 已移除）。

## R2 三层模型路由（Model Router 默认策略，唯一规范源 `core/src/router.rs`）

优先级顺序：

1. 用户强制指定（force != auto）→ 直接用指定后端
2. 简单提示词 && 端侧非 STUB && 非低延迟模式 → JNI（端侧 2B-4B）
3. 桌面可达 && （中/高复杂度）→ 桌面 gRPC（7B-70B）
4. 桌面可达 && 端侧 STUB → 桌面 gRPC
5. 云端已配置 && 有网络 → 云端
6. 端侧非 STUB → JNI 兜底
7. 全部不可用 → 返回明确错误（**禁止静默假数据、静默空回复**）

复杂度判定：含 ` ``` ` 代码块或长度 > 1024 → Hard；长度 ≤ 256 → Simple；其余 Medium。

Android 端 `ModelRouter.kt` 是同构实现，必须与本策略表逐条一致。

## R3 平台优先级：Windows 优先

- Windows：默认编译，走 UI Automation（`uiautomation` crate）+ Win32 鼠标（`winapi`）。
- macOS/Linux：代码分支保留但**默认不启用**，必须显式 cargo feature 才编译对应分支：
  - `cargo build --features macos-ax`
  - `cargo build --features linux-atspi`
- 未启用或未实现的平台必须返回明确错误：
  `{"error": "...", "platform": "<os>"}`，禁止返回伪造的屏幕树。
- 两个分支当前为保留骨架（实现待接入），启用后同样返回明确状态而非假数据。

## R4 llama.cpp 构建开关（默认 STUB）

| 端 | 默认（STUB） | 全量推理 |
|---|---|---|
| Android | `./gradlew assembleDebug` | `./gradlew assembleDebug -PenableLlamaCpp=true` |
| 桌面 Rust | `cargo build --release` | `cargo build --release --features full-llama` |

- Android 全量需把 llama.cpp 源码放到 `android/app/src/main/cpp/llama_cpp/`（已被 `.gitignore` 忽略）；缺源码时 CMake **报错终止**，不静默降级。
- 桌面全量需设置 `LLAMA_LIB_DIR` 指向已编译的 llama.cpp 静态库目录（`build.rs` 读取并注入链接路径）。
- STUB 必须在运行时显式输出 `[STUB] 当前为桩实现，推理结果不可用`，禁止伪装成成功推理。
- **版本登记**（JNI/FFI 所针对的 llama.cpp API 版本）：
  - 目标窗口：b4100 前后（`llama_sampler_*` 与 `llama_new_context_with_model` 并存、model 版 `llama_tokenize` / `llama_token_to_piece`）
  - 状态：**UNVERIFIED** —— 当前容器无 NDK / llama.cpp 源码，全量路径尚未编译验证；首次通过编译后在此登记确认的 tag。

## R5 通信协议分层

| 链路 | 协议 | 实现位置 |
|---|---|---|
| Dart UI ↔ Kotlin 执行层 | Platform Channel（进程内） | `lib/platform/agent_channel.dart` ↔ `MainActivity.kt` |
| Dart UI ↔ Rust 桌面后端 | gRPC over localhost | 预留；生成脚本 `scripts/gen-proto.sh`（当前未接入） |
| Kotlin ↔ C/C++ | JNI | `ModelBridge.kt` ↔ `llama_jni.cpp` |
| Android ↔ 桌面（自动化 + 推理） | gRPC over LAN | `DesktopInferenceClient.kt` ↔ tonic `:50051` |
| 任何端 ↔ 云端 | HTTP/2 + SSE | Dart `AIService` |

## R6 Agent Core 规范

- 任务状态机与路由策略的唯一规范源：`core/`（Rust crate `yaya-core`），`cargo test -p yaya-core` 为可执行规范。
- Android 端 `com.yaya.ai.core.ModelRouter` / `TaskState.kt` 为同构实现，策略必须与 `core/src/router.rs` 一致。
- 跨端共享二进制（JNI 链接 core）为后续优化，本期不做（避免引入 cargo-ndk 与预编译 .so，违背「默认 STUB、CI 不依赖手动工具链」）。

## R7 Proto 生�物必须提交到仓库，CI 检查一致性

- `proto/agent.proto` 为契约唯一源，Java/Kotlin/Dart 端生成物**必须**提交到仓库（`android/app/src/main/java/`, `lib/`）。
- CI 工作流 `.github/workflows/proto.yml` 将在每次 push/PR 时运行生成并**检查一致性**，若生成物与提交的版本不一致则 **fail**。
- 开发者如需修改契约，必须：1. 修改 `proto/agent.proto` 2. 运行 `bash scripts/gen-proto.sh` 3. 检查生成结果是否符预期 4. 提交所有变更（proto 文件 + 生成物）。
- 禁止仅修改生成物而不修改源 proto，CI 会拦截。

## R8 CI 默认 STUB，full-llama 构建不进 CI，仅本地手动验证

- GitHub Actions 中的 Rust 桌面 job（`.github/workflows/rust-desktop.yml`）默认**不**启用 `full-llama` feature，确保 CI 快速通过且无需 llama.cpp 源码。
- 完整 llama.cpp 构建（`cargo build --release --features full-llama`）**仅在本地进行**，需设置 `LLAMA_LIB_DIR` 并编译对应源码，CI 中将被跳过并记录警告。
- Android 构建同理：`flutter build apk` 使用 STUB 推理，完整推理需 `-PenableLlamaCpp=true` 且 llama.cpp 源码就位，CI 中默认不编译。

## R9 生成物路径固定与提交责任

- **Java 端**：`proto/agent.proto` 的 `java_package = "com.yaya.ai.proto"` 与 `option java_multiple_files = true` 共同作用，`scripts/gen-proto.sh` 生成物**必须**落到 `android/app/src/main/java/com/yaya/ai/proto/`。开发者如需修改 Java proto 映射，必须同步修改 proto 文件或手写映射，CI 将检查该目录下的变更。

- **Dart 端**：生成物**必须**提交到 `lib/generated/`。`scripts/gen-proto.sh` 会自动创建该目录并输出文件，CI 会检查该目录下的变更。

- **Rust 端**：当前**不自动生成** proto 映射文件，由手写 `tonic::codegen` 或手动 `.proto` 解析为 Rust 结构体。CI 中不执行任何 Rust proto 代码生成，避免在 proto job 中拉取 rust-toolchain 与 cargo 编译开销。如需生成，请在 `core/` 下手动运行 `cargo install tonic-protoc` 并自行管理输出路径。

- **CI 校验**：`.github/workflows/proto.yml` 中的 `git diff --exit-code` 将分别检查 `android/app/src/main/java/com/yaya/ai/proto/` 与 `lib/generated/` 是否有非预期变更。若有变更且非由 `bash scripts/gen-proto.sh` 产生，构建将 **fail**。

- **提交规范**：提交时必须按以下顺序包含文件：
  1. `proto/agent.proto`（源头）
  2. `android/app/src/main/java/com/yaya/ai/proto/`（Java 生成物）
  3. `lib/generated/`（Dart 生成物）
  4. `core/src/generated/`（Rust 手写映射，若存在）
  5. `scripts/gen-proto.sh`（如有修改）

## 构建与验证

- Android：`flutter build apk --release`。CI：`.github/workflows/flutter-android.yml`
  （Flutter 3.22.2 / JDK 17 / Gradle 8.7 wrapper 已入库）。
- 桌面：仓库根 `Cargo.toml` 为 workspace（`core` + `desktop/yaya-agent-server`）；
  `cargo check --all-targets`、`cargo test -p yaya-core`。CI desktop job 在 ubuntu + windows 上执行，
  windows job 会真实 typecheck `#[cfg(windows)]` 的 UIA 代码。
- 本地工具链不全时（无 flutter/cargo/protoc），以 CI 结果为准；标记 UNVERIFIED 的路径不得宣称已验证。