# YAYai — Android AI Agent 客户端

运行在 Android 设备上的 AI Agent 客户端：通过 AI 观察并操作设备界面、执行终端命令，
目标是让模型在移动端真正发挥全部能力（自主多步、工具调用、可中断、可观测）。

> 本仓库为**单端工程**（仅 Android）。架构规则见 [`AGENTS.md`](AGENTS.md)。

## 架构

```
Flutter UI (Dart)
  │  Platform Channel
  ▼
Kotlin 执行层（AgentHost / MainActivity）
  │  无障碍服务（读屏·点击·输入·滑动·系统操作·启动应用）
  │  proot 终端容器 / llama.cpp（JNI）
  │  JNI ▼
Rust Agent Core（crate `yaya-core`，唯一规范源）
  ├─ run.rs       ReAct 循环机（观察→决策→执行→回填）
  ├─ state.rs     任务状态机
  ├─ router.rs    三层模型路由（端侧 / 桌面[保留] / 云端）
  ├─ tools.rs     统一工具层（function-calling schema + 派发，含 MCP）
  ├─ openai.rs    OpenAI 兼容协议（含流式 tool_calls 解析）
  ├─ cloud.rs     云端后端（reqwest + rustls，feature `cloud-http`）
  ├─ mcp.rs       MCP 工具接入（命名空间 + McpClient trait）
  └─ observer.rs / executor.rs / model.rs   观察/动作/模型三个平台 trait
```

**归属原则**：循环机是编排者，属 Core（Rust，唯一实现）；Android 经 JNI 共享；
平台差异由 `ScreenObserver` / `ActionExecutor` / `ModelBackend` / `McpClient` 四个 trait 注入。
Kotlin/Dart 侧**不得**重复实现循环、路由或工具层。

## 功能

- **AI Agent 闭环**：ReAct 多步循环 + OpenAI function-calling（含流式 `tool_calls`），
  支持最大步数与中断取消。
- **设备控制工具**：`observe_screen` / `tap_node` / `input_text` / `scroll_node` /
  `swipe` / `system_action` / `launch_app`。
- **终端工具**：`terminal_exec`（proot Debian 容器，同步取回输出，带超时）。
- **端侧推理**：llama.cpp JNI（默认 STUB，需显式开启）。
- **MCP 工具**：stdio 服务器的工具并入统一工具层（`mcp__<server>__<tool>`），
  含 `initialize` 握手、按 `id` 匹配读取与超时；未启用时工具集与不接入时完全一致。

## 构建与验证

```bash
# 1. Rust Core（本地可完整验证）
cargo test -p yaya-core
cargo check -p yaya-core --features cloud-http

# 2. 交叉编译 JNI 库 → android/app/src/main/jniLibs/
ANDROID_NDK_HOME=<ndk 路径> bash scripts/build-android.sh
# （仅需对 Android target 做编译检查时，无需 NDK：
#  cargo check -p yaya-core-jni --target aarch64-linux-android --no-default-features）

# 3. 构建 APK（默认 STUB 推理）
flutter build apk --release
# 全量端侧推理：flutter build apk --release 前将 llama.cpp 源码放入
# android/app/src/main/cpp/llama_cpp/ 并加 -PenableLlamaCpp=true
```

CI：`.github/workflows/rust-core.yml`（Core 测试）、
`.github/workflows/flutter-android.yml`（NDK + `scripts/build-android.sh` + APK）。

## 配置

在「设置」中填入 OpenAI 兼容的 Base URL、API Key 与模型名即可使用云端后端。

## 许可证

MIT License