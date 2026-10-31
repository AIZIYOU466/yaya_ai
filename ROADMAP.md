# YAYai 演进路线

本文件定义 YAYai 向顶尖 Android Agent 演进的任务清单、优先级与依赖。
**任务完成时勾选 checkbox 并追加到「更新记录」**。规则性内容见 `AGENTS.md`，功能介绍见 `README.md`。

## 阶段目标

- **阶段 1**：向 AiCode 靠齐 —— 补齐 AiCode 已有的核心 Agent 能力（12 任务）
- **阶段 2**：超越 AiCode —— 落实顶尖 Agent 判据（8 任务）
- **阶段 3**：从聊天壳到 IDE 工作台 —— 补齐 IDE 型产品形态，并让端侧推理与多模态从「代码就绪」变为「真机可用」（16 任务）
- **阶段 4**：内核加固 —— 把内核里的「假设」换成「证据」（录制回放 / 能力探测 / 验证 / 契约 / 端点池 / 可恢复）（6 任务）

---

## 产品形态决策（46，2026-10-11 定稿）

- **主界面** = 聊天 + 任务卡（当前形态）；**终态愿景** = 任务控制台（节点图 / 委托单 / 任务栈，待可靠性验证后启用）；**工作区检视器**（文件浏览 / 编辑器预览 / Git 状态）= 工具层，保留不深化——它是用户验证 AI 产物（R15 不假设成功）的唯一通道；**终端**属核心能力（proot 容器），非 IDE 工具
- **原则**：可靠性先行、UI 推迟——任务控制台成立的前提是 core 可靠（任务 40 / 42 / 43 / 44 / 45 全部落地后才启用愿景形态）
- **任务卡数据消费**：事件流 → UI 状态推导表（纯 Kotlin，不碰 core，1-2 天）；`DegradeSnapshot` 是快照非增量，需从事件流推导当前活跃任务与最近完成 tool_call
- **push-resume**：留接口位不实现（FCM + 后端协调第一版不做；检查点设计不得堵死此路径，见任务 44）
- **阶段 3 路径说明**：阶段 3 各任务描述的 `lib/screens/*.dart` 路径已因 Flutter 整体移除（2026-10 迁移 Kotlin/Compose）而作废，实际实现均为 Compose 页面

---

## 阶段 1：向 AiCode 靠齐

### Phase 1A：信任工程基础设施（P0）

- [x] **1. 三种运行模式（BUILD / PLAN / AUTO）**
  - 参考：AiCode `feature/agent/domain/tool/mode/`
  - 涉及：`core/src/agent/run.rs`（RunConfig 加 `mode`）/ `tools.rs`（PLAN 禁用 `terminal_exec`、`clipboard_write`）/ `executor.rs`（policy 检查）
  - 验收：PLAN 模式 `terminal_exec` 调用返回明确错误；AUTO 模式跳过授权

- [x] **2. 检查点与撤销**
  - 参考：AiCode `feature/agent/domain/checkpoint/`
  - 实现：Android 侧（`AgentDatabase` 检查点表 + `AgentHost` 工具执行前自动快照 + UI 历史按钮回滚）；core 无需改动（消息历史在数据库中）
  - 快照内容：会话 `messages`（user / tool / assistant / system）
  - 验收：任务中可随时回滚到任意检查点（已达成）

- [x] **3. 工具授权策略**
  - 参考：AiCode `ToolPermissionManager` + `ToolPermissionPolicyEngine`
  - 涉及：新增 `core/src/agent/permission.rs`
  - 每个工具定义撤销成本等级（可逆 / 半可逆 / 不可逆）；RUN 模式高风险先问、低风险先做
  - 验收：可配置授权策略，PLAN / AUTO 影响授权行为

### Phase 1B：可观测与成本（P0）

- [x] **4. Token 统计**
  - 涉及：`openai.rs` 解析 `usage` 字段；`events.rs` 加 `Usage` 事件；Flutter UI 显示
  - 验收：会话结束能看到总 token、prompt/completion 拆分、估算费用

- [x] **5. 决策原因码**
  - 涉及：`events.rs` Event 加 `reason` 字段；`run.rs` 每次决策填 reason
  - 验收：事件流能追溯到每次决策的原因

### Phase 1C：持久化（P0）

- [x] **6. 会话持久化（SQLite）**
  - 参考：AiCode `feature/agent/data/local/`
  - 实现：`AgentDatabase.kt`（SQLiteOpenHelper 零依赖；sessions / messages / checkpoints 表）；`AgentHost.onEvent` 同步写库
  - 验收：杀进程重启能恢复会话与对话历史（Dart 启动经 `loadRecentSession` 恢复）
  - 备注：用 SQLite 而非 Room——容器内无 Flutter SDK 无法验证 gradle 依赖，零依赖方案风险最低，功能等价

- [x] **7. 数据库迁移框架**
  - 实现：`DB_VERSION` 常量 + `onUpgrade` CASE 分支逐级迁移（`AgentDatabase`）
  - 验收：跨版本升级不丢数据（机制就绪）；编号由常量管理（单库项目暂不需要对账脚本）

### Phase 1D：能力扩展（P1）

- [x] **8. 子代理**
  - 参考：AiCode `feature/agent/subagent/`
  - 实现：`core/src/agent/subagent.rs` —— `subagent` 工具在 core 内**顺序递归**运行子循环（独立上下文，同一平台能力），返回最终文本；子任务受同一模式策略约束；跨端零改动（事件流天然覆盖）
  - 验收：主 Agent 派生子任务并取回结果（已达成）；真并行（多线程）留待后续
  - 备注：BUILD 下子任务内部不可逆工具仍会触发授权确认；PLAN 模式拦截 subagent

- [x] **9. 技能系统**
  - 参考：AiCode Skills（`SKILL.md`）
  - 实现：`core/src/agent/skill.rs`（解析 frontmatter + 扫描目录）；`RunConfig.skills_dir` 注入正文到系统提示词；Kotlin `AgentHost.skillsDir()` 提供 `filesDir/skills/`
  - 验收：能加载技能目录，技能在系统提示词中生效（已达成）

- [x] **10. 自动记忆**
  - 参考：AiCode Memory
  - 实现：`core/src/agent/memory.rs`（`MemoryStore` trait + 5 个工具：list/read/save/edit/delete）；清单注入系统提示词；Kotlin `AgentDatabase` 新增 `memories` 表（DB v2 迁移）
  - 验收：能跨会话读写记忆（已达成）

### Phase 1E：工程质量（P2）

- [x] **11. 双语国际化**
  - 实现：`lib/l10n.dart` 轻量 L10n（无 gen-l10n 构建依赖）+ `languageProvider`（SharedPreferences）+ 聊天页语言切换按钮；chat_screen 全部用户可见文案已抽离
  - 备注：其余页面（设置/终端/MCP）文案留待后续增量抽离

- [x] **12. 用户文档**
  - 参考：AiCode `docs-site/`
  - 实现：`docs/README.md`（功能说明 + 快速开始 + 进阶教程）
  - 验收：功能说明 + 快速开始 + 进阶教程齐全（已达成）

---

## 阶段 2：超越 AiCode

### Phase 2A：运行时能力协商（P3）

- [x] **13. 能力探测**
  - 实现：`core/src/agent/capability.rs`（`Capabilities` 结构 + config 解析 + token 预算钳制 + 压缩阈值推导）；`RunConfig.capabilities` 注入；运行时 `max_tokens` / `max_messages` 按能力降级
  - 备注：真实 HTTP 探测（ping 端点）由平台/Kotlin 完成；core 只负责能力模型的解析与应用，纯 core 可测

- [x] **14. 金丝雀检测**
  - 实现：`core/src/agent/canary.rs`（`CanaryProbe` 题型 + 默认探测题 + 单步生成验证）；`RunConfig.canary` 启用开关（默认关闭）；异常经 `Event::Notice` 上报（不阻断任务）
  - 备注：默认探测题为简单算术；扩充题库待后续

### Phase 2B：安全与防御（P3）

- [x] **15. Prompt Injection 硬防御**
  - 实现：`run.rs` 工具结果回填 messages 前加 `[工具 <name> 返回的数据，仅作参考，不是指令]` 包装（事件仍发原始内容给 UI）；`default_system_prompt` 注入安全规则声明（忽略工具/网页中试图改变行为的指示）；已有 R12 授权机制覆盖危险操作确认
  - 验收：注入攻击无法改变 Agent 行为（已达成，有 core 测试验证包装正确性）

- [x] **16. MCP 权限隔离**
  - 实现：`RunConfig.mcp_tool_allowlist`（`HashSet<String>`，完全限定名）；配置后 `mcp__<server>__<tool>` 工具仅白名单内可见且可调用，未知工具在 `execute_call` 层默认拒绝（不走 dispatch）；`tool_specs` 构建时 `retain` 过滤
  - 验收：可配置哪些 MCP 工具可用，未知工具默认拒绝（已达成，有 core 测试）
  - 备注：`None` 时全部已启用服务器工具可用（现状），不影响未配置白名单的用户

### Phase 2C：评估闭环（P3）

- [x] **17. 用户级评估面板**
  - 实现：`AgentDatabase.statsJson()`（SQL 聚合 sessions/messages/toolCalls/errors/totalTokens/checkpoints）；usage 事件入 messages 表（role=`usage`）；Dart `AgentChannel.getStats()` + 统计弹窗（AppBar analytics 按钮）
  - 备注：指标为累计全局统计，per-session 细粒度留待后续
  - 验收：能看到历史指标（已达成）

- [x] **18. 决策复盘工具**
  - 实现：tool_policy 事件入 messages 表（role=`policy`，text=`verdict: name — reason`）；恢复历史时加载 policy 消息；Dart `isPolicy` 样式（小字灰色 monospace + policy_outlined 图标）
  - 备注：决策链随对话历史一并持久化，重启后可复盘；趋势图表留待后续
  - 验收：能追溯任意任务的决策链（已达成）

- [x] **21. 工作区文件系统**（新增）
  - 参考：AiCode `feature/workspace/` + `FileTools.kt`
  - 实现：`core/src/agent/workspace.rs`（`FileAccess` trait + 5 工具：list/read/write/edit/delete + 2000 行/200KB 窗口 + start_line 分段）；Kotlin `WorkspaceFileAccess.kt`（`filesDir/workspace/` + 路径穿越校验）；JNI `JniFileAccess`；Dart 工作区浏览弹窗
  - 权限：list/read 只读放行（PLAN 可用）；write/edit/delete 不可逆（BUILD 需确认）
  - 验收：AI 可直接在工作区建文件/读写/编辑/删除、开发软件（已达成，core 88 测试）

### Phase 2D：文化与治理（P3）

- [x] **19. 决策原则文档深化**
  - 目标：AGENTS.md 从当前 ~6KB 扩到 10KB+
  - 实现：AGENTS.md 新增 R15（决策原则：客观判据优先）、R16（变更流程）、R17（发版流程）；覆盖决策原则、审查流程、构建验证规则
  - 验收：AGENTS.md 覆盖所有关键决策点（已达成，含 17 条规则）

- [x] **20. 环境验证 oracle**
  - 实现：`core/src/agent/verifier.rs`（`VerifyPlan`：`NoCheck` / `ReadBack`；`plan_for` 按工具名判定；`verify` 执行验证）；集成到 `run.rs` 工具循环（执行后、事件前自动验证，失败回填原因给模型标记失败）
  - 当前覆盖：`clipboard_write` 读回验证；其余工具 `NoCheck`（通知、命令输出视为已执行）
  - 验收：Agent 不假设成功，可验证工具调用有客观验证（已达成，有 core 测试）

## 阶段 3：从聊天壳到 IDE 工作台

阶段 1-2 补齐了 Agent 内核（循环/路由/权限/记忆/技能/子代理/评估/注入防御），
yaya_ai 已是「内核 + 聊天壳」。阶段 3 的目标是补齐 IDE 型客户端的产品形态，
并让端侧推理与多模态从「代码就绪」变为「真机可用」，追平 AiCode 的外围能力。

### Phase 3A：核心工作台（P0）

- [x] **22. 文件浏览与代码编辑器**
  - 参考：AiCode `guide/files.md`
  - 实现：新建 `lib/screens/files_screen.dart`（缩进树形目录、文件类型图标、长按新建/重命名/删除、按需展开加载）与 `lib/screens/editor_screen.dart`（编辑/预览双模式：等宽 TextField + 快捷符号栏 + 撤销重做 + 未保存退出确认；Markdown 用 MarkdownWidget 渲染，代码用零依赖语法高亮器）；Kotlin `MainActivity` 新增 workspaceRead/workspaceWrite/workspaceDelete 通道（单文件读写走后台线程）；`home_screen` 加「文件」tab
  - 备注：语法高亮为纯 Dart 实现（`lib/widgets/syntax_highlighter.dart`），预览模式生效；实时编辑高亮留待后续
  - 验收：用户可在 App 内浏览工作区文件树、点开编辑、保存；编辑器支持主流语言语法高亮（已达成，Dart/Kotlin 未真机验证）

- [x] **23. Git 版本管理 UI**
  - 参考：AiCode `guide/git.md`
  - 实现：新建 `lib/screens/git_screen.dart`（状态/分支/提交三标签页；`git status --porcelain -z` 解析 + 分组，暂存/取消暂存/全部回退/提交，分支切换/新建/删除，提交历史 `git log --oneline`，文件 diff 与提交详情底部弹层）；Kotlin `GitHost.kt`（经 `ProotManager.runCommandBlocking` 对容器 `/workspace` 执行 git，参数数组逐词 shell 转义防注入，含 git 可用性/仓库检测）；`ProotManager` 把工作区 `filesDir/workspace` bind 进容器 `/workspace`（与 file 工具同目录，修复 file/terminal 不一致）；文件页 AppBar 加 Git 入口
  - 前置：容器需装 git（Alpine `apk add git`），Git 页检测到缺 git 时提示
  - 依赖：任务 22（复用 file 工具工作区语义；diff 为文本展示，未复用编辑器）
  - 验收：用户可在 App 内可视化管理 Git，不依赖终端敲命令（已达成，Dart/Kotlin 未真机验证）

- [x] **24. 多会话管理**
  - 参考：AiCode `guide/chat.md` 会话列表
  - 实现：`AgentDatabase` 新增 `sessionsJson`（按 updated_at 倒序 + 消息数）/`renameSession`/`deleteSession`（级联消息+检查点）/`sessionJson`（指定会话）；`AgentHost`/`MainActivity`/`AgentChannel` 新增 listSessions/loadSession/renameSession/deleteSession 通道；`lib/screens/chat_screen.dart` AppBar 加会话按钮，底部弹层会话列表（新建/切换/重命名/删除，当前会话高亮，删除当前后自动加载最近会话）
  - 备注：yaya_ai 为底部导航架构，会话列表用底部弹层承载（AiCode 的侧边栏方案不适用）；未做置顶与按时间分组
  - 验收：用户可管理多个会话并在其间切换，进程重启后恢复最近会话（已达成，Dart/Kotlin 未真机验证）

### Phase 3B：模型与多模态（P0）

- [x] **25. 多模型提供商**（2026-10-25 收尾：core 前序已落地（`provider.rs` 三协议 + `AgentCore.register_endpoint` 多实例 + `run.rs::pick_backend` + `jni config.providers`）；本日补齐 Compose/Kotlin 侧——`Providers.kt`（`ModelProvider` / `ProviderModel` / `ModelCaps` + `ProviderStore` 旧单端点配置迁移）、`SettingsScreen` 提供商增删改/模型拉取/能力标签（tools/image/ctx）、`ChatScreen` 模型切换弹层与 `config.providers` + `preferredEndpoint` + `capabilities` 下发、`MainActivity.listModels`（OpenAI/Anthropic/Gemini 三协议拉取，失败显式回包）；Kotlin/Compose 侧 UNVERIFIED（容器无法真机验证），core `#[test]` 199）
  - 参考：AiCode `guide/providers.md`
  - 涉及：`lib/screens/config_screen.dart`（从单端点改为多提供商列表）；`providers.dart`（提供商模型：类型/Key/BaseURL/模型列表）；Kotlin `AgentHost`（config 下发多提供商）；core `cloud.rs`（按协议适配 OpenAI/Anthropic/Gemini）
  - 功能：多提供商（OpenAI/Anthropic/Gemini 三协议）、模型拉取与手动添加、能力标签（Image/Tools/上下文长度）、模型选择弹窗
  - 验收：用户可配置多个模型服务并在对话中切换模型

- [x] **26. 多模态图片输入与展示**（2026-10-25 落地：core `RunConfig.user_images`（首条 user 消息 `Content::Parts`，三协议分别序列化 image_url / Anthropic base64 image / Gemini inlineData，含 run/provider 测试）；JNI `config.userImages` 仅接受 `data:image/...;base64,`；Compose 输入区相册多选（最多 4 张）、端侧 1280px JPEG 压缩、待发送缩略图/移除、图片消息气泡、助手 Markdown 图片内联解析（跳过代码块、流式期间不解析半截 URL）、全屏双击/双指缩放；消息表 v3 增 `images` 列，图片落 `filesDir/chat_images/` 受管路径并随会话/检查点恢复，删会话与回滚会清理孤儿文件；模型 `image` 能力关闭时平台侧拦截。Kotlin/Compose 侧 UNVERIFIED（容器无法真机验证）。已知边界：本地（llama）后端的多模态路由留待任务 31）
  - 参考：AiCode `guide/chat.md` 图片按钮与全屏看图
  - 涉及：`lib/screens/chat_screen.dart`（图片附件按钮 + 预览卡 + 全屏看大图）；`AgentChannel`（图片 base64 下发）；core `model.rs` 的 `Content::Parts`/`user_with_images`（已就绪，激活即可）
  - 功能：用户上传相册图片作为附件、AI 回复中的图片可点开全屏、双指缩放
  - 备注：core 多模态消息层已完整（含序列化测试），本任务只补 UI 与附件下发链路；与「不操控设备」定位兼容（用户主动上传，非自动截屏）
  - 验收：用户可发图给 AI 并收到带图的回复，图片可全屏查看

### Phase 3C：交互体验（P1）

- [x] **27. 消息队列 → 任务队列**（2026-10-25 勾选：实现已落地，待真机验证）
  - 参考：AiCode `guide/chat.md` 消息队列
  - 实现：`ChatScreen.kt` 排队列表（含图片附件）+ 队列面板逐项移除 + 当前轮结束自动发送下一条；core `signals.request_suspend()` 在下一轮前存档 `LoopState` 并发 `Event::Suspended`（不丢弃）；`AgentHost.suspend()` → JNI；UI 挂起态可经 `resumeAgent` 从断点续跑；忙时 `/命令` 同样入队
  - 功能：AI 忙时输入排队、当前轮结束后自动发送下一条、点停止 = **挂起当前任务**（OnSuspend，配合任务 43/44，非丢弃）、可移除排队项
  - 验收：AI 工作时可继续输入并排队，不打断当前任务（Compose 侧 UNVERIFIED，容器内无真机）

- [x] **28. 斜杠命令**（2026-10-25 勾选：`/status` 已落地；`/compress` 按 46 决策暂缓）
  - 参考：AiCode `/status`、`/compress`
  - 实现：`ChatScreen.kt` 命令表（`/` 前缀识别）+ `/status` 输出模型/模式/token 累计/队列长度/暂停态；未知命令显式提示可用命令；忙或暂停时命令入队
  - 功能：`/status`（当前会话状态：token/模型/模式）、`/compress`（手动触发上下文压缩）；命令在 AI 忙时排队
  - 决策（46）：仅保留 `/status`；`/compress` 等压缩增强稳定后再启用
  - 验收：输入 `/` 可用斜杠命令（已达成，Compose 侧 UNVERIFIED）

- [x] **29. 上下文压缩增强**（2026-10-25 勾选：模型摘要压缩已落地）
  - 实现：`run.rs::compact_with_summary`（压缩边界与截断一致，保留 system + 最近 `max-2` 条，且下界落在 tool 结果时向前回退，避免悬空 tool 消息）+ `compact_with_backend`（有后端时用 `SUMMARY_SYSTEM_PROMPT` 生成事实性摘要：24k 输入上限、`max_tokens≤512`、`stream=false`；摘要为空/后端失败/无后端时回退 `compact_messages` 并发 `Notice` 显式说明）；摘要用量以 `Event::Usage{kind:"compact"}` 计入总量
  - 功能：超阈值时用模型对中间历史生成摘要（保留目标与约束、已完成步骤与结果、关键文件/命令/错误、未完成事项），保留上下文连续性而非简单丢弃
  - 验收：长任务压缩后仍能引用早期上下文；有 core 测试（摘要生效 / 摘要失败回退 / 无后端回退）

- [x] **30. 用量透明度**（2026-10-25 勾选：单条明细 + 会话累计已落地）
  - 参考：AiCode `guide/token-stats.md`
  - 实现：`Usage` 事件扩 `cached_tokens` / `duration_ms` / `kind`；`AgentDatabase` v4 迁移加 `prompt_tokens` / `completion_tokens` / `cached_tokens` / `duration_ms` / `cost_usd` / `usage_kind` 列并在恢复历史时回填；`ChatScreen` 回复气泡下显示 `↑prompt ↓completion`、缓存命中率、压缩用量与耗时，状态行显示会话累计耗时与费用估算
  - 功能：每条回复显示 prompt/completion token、缓存命中率、本轮耗时；会话与全局累计费用估算
  - 验收：用户能看到每次回复的成本明细与累计费用（Compose 侧 UNVERIFIED）
  - 决策（46）：并入任务卡（当前会话的成本/耗时/状态单条呈现），不做独立面板

### Phase 3D：端侧与并行（P1）

- [ ] **31. 端侧推理落地**
  - 现状：链路齐全（`llama_jni.cpp` + `ModelBridge.kt` + JNI `LocalBackend` + 路由 `local_ok`），但默认 STUB，llama.cpp 未编译，状态 UNVERIFIED
  - 涉及：`android/app/src/main/cpp/llama_cpp/`（放入 llama.cpp 源码）；`scripts/build-android.sh`（`-PenableLlamaCpp=true`）；`lib/screens/config_screen.dart`（模型文件选择/下载 UI）；`AgentHost`（`localAvailable` + `modelPath` 下发）
  - 功能：真机验证 llama.cpp 全量构建、.gguf 模型文件管理、端侧 function-calling（`local_parse.rs` 的 `<tool_call>` 解析对真实小模型的可靠性验证）
  - 验收（降级）：llama.cpp 全量编译通过并登记 tag（R4 转 VERIFIED）；`.gguf` 模型路径接入（`ModelBridge.currentModelPath` 有来源）；路由 `local_ok` 分支生效。不追求离线全功能——端侧小模型做工具调用非主路径，其解析准确率并入任务 45 评测基准

- [x] **32. 子代理真并行与自定义**（2026-10-31 落地：core `subagent.rs` 重写——`SubagentDef`（`RunConfig.subagents`，camelCase 与平台 JSON 对齐：说明/专属指令/专属模型/工具白名单/步数）+ 内置 `explore` 只读定义（白名单固定文件列/读、记忆列/读、剪贴板读，平台只可启停，覆盖定义按字段合并防夹带）+ `RunConfig.tool_allowlist`（工具集 `retain` 收窄 + 未列入调用显式拒绝，子代理与父级取交集）+ `run_subagents_parallel`（`std::thread::scope` 并发，`max_parallel_subagents` 分批上限 1–8、平台 `SubagentFork` 派生独立 `AgentCore`、子线程事件按调用下标缓冲后确定性重放、无工厂回退顺序递归）+ `Event::Subagent{id,agent,phase,detail}` 标记生命周期；`run.rs` 每轮工具循环前预派发本轮全部 subagent 调用（PLAN 拒绝与白名单外调用不预派发）并按调用下标回填结果，BUILD 授权语义不变；JNI 新增 `JniSubagentFork`（重建同一套平台 trait/本地/多提供商/端点实例/MCP）+ `JniHost::env` 子线程 daemon 挂载 + `config.subagents`/`maxParallelSubagents` 解析；Compose 新增 `Subagents.kt`（SharedPreferences `flutter.ai_subagents` / `flutter.ai_max_parallel`）、设置页子代理卡片与编辑弹窗（名称/说明/模型/指令/工具白名单/步数/启停、并行上限 1–5）、对话页任务卡子代理状态 chip + 点击查看子会话（任务/结果摘要）、`AgentHost.requestApproval` 队列锁串行化并行授权。core `#[test]` 209 + 契约 7；三 ABI `.so` 交叉编译、`assembleDebug`、`testDebugUnitTest`（11 项）通过；Kotlin/Compose 侧 UNVERIFIED（容器内无真机）。
  - 现状（已解决）：`subagent.rs` 曾为顺序递归，单线程
  - 涉及：`core/src/agent/subagent.rs`（多线程并行 + 事件流标记子代理 id）；`core/src/agent/run.rs`（并行调度与结果汇总）；Kotlin `AgentHost`（JNI 多线程回调）；Compose `ui/ChatScreen.kt`（子代理状态指示 + 点击进入子会话）
  - 功能：最多 N 个子代理并行、自定义子代理（模型/工具白名单/专属提示词）、内置 Explore 只读代理、设置内启停管理
  - 限制：proot 容器为单实例，子代理的终端命令仍串行；并行子代理各自持有独立降级/行为态（不共享父任务健康环）
  - 验收：主 Agent 可派发多个并行子代理，UI 与消息队列不阻塞（父循环等本批结果再生成，符合工具回填语义；子代理事件批次结束后按调用下标回传，保证可重放）；core 测试用屏障证明真并发（顺序执行会超时）。Compose 侧 UNVERIFIED

### Phase 3E：扩展与成熟度（P2）

- [x] **33. 终端多标签与辅助按键**（2026-10-25 落地；光标样式未做，留待后续）
  - 参考：AiCode `guide/terminal.md`
  - 实现：`TerminalScreen.kt` 多标签（最多 5 个，每个标签独立回滚缓冲与命令历史，可新建/关闭/切换）、辅助按键栏（Esc / Tab / Ctrl-C 中断 / ←→ 光标 / ↑↓ 历史 / `|` `/` `~` `-` `_`）、字号 9–22 与 3 档墨色配色（持久化到 `FlutterSharedPreferences`）；新增 `TerminalLog.kt`（线程安全、最多 20 次命令 × 2000 行）+ `ProotManager` 在 `terminal_exec` 路径写日志（`lineSource=agent`，哨兵行不入日志），终端页以只读「AI」标签展示模型执行过的命令与输出/退出码
  - 现状（已改善）：单标签、单命令输入 → 多标签 + 辅助按键 + AI 命令可见
  - 限制：proot 为**单容器单进程**，多标签只是独立回滚缓冲，命令串行执行（真并行会话需重构容器管理）；光标样式未实现
  - 验收：终端支持多标签与辅助按键，AI 执行命令的标签也出现在列表（已达成；Compose 侧 UNVERIFIED，容器内无真机；`TerminalLogTest` 5 项 JVM 单测通过）

- [x] **34. 备份与同步**（2026-10-25 落地：加密导出/导入；工作区同步未做，留待后续）
  - 参考：AiCode `guide/backup.md`、`guide/sync.md`
  - 涉及：新建 `android/.../ui/BackupScreen.kt`（抽屉「备份」入口，SAF 选文件，无存储权限）；Kotlin `BackupHost.kt`（导出/恢复配置 + 工作区 + 技能）；`BackupFormat.kt`（纯 JVM 加密容器 + zip + zip-slip 防护）；`BackupPrefs.kt`（SharedPreferences XML 解析）
  - 功能：密码加密导出导入（PBKDF2-HMAC-SHA1 210k 迭代 + AES-256-GCM 认证加密）；配置 + 工作区 + 技能；`src/test` JVM 单测覆盖加密往返 / 错误密码 / zip-slip / 配置类型解析
  - 恢复语义：配置经 SharedPreferences API 逐键写回（不覆盖 XML，避免进程内缓存反向覆盖而立即生效）；工作区/技能合并覆盖；不恢复任务断点（`agent_state`）避免自动续跑旧任务
  - 不含：会话/记忆数据库（运行中覆盖 SQLite 有损坏风险）、rootfs 镜像、工作区同步（SFTP/FTP 未做）
  - 验收：用户可备份配置、工作区与技能并在新设备恢复（Kotlin/Compose 侧 UNVERIFIED，容器内无真机；JVM 单测与 `assembleDebug` 已验证）

- [ ] **35. 远程 SSH 后端**
  - 参考：AiCode `guide/remote-ssh.md`
  - 涉及：Kotlin `SshHost.kt`（SSH 连接 + 远程命令执行）；`core/src/agent/router.rs`（desktop 分支改为 SSH 语义或新增 SSH 后端）；`lib/screens/config_screen.dart`（SSH 配置入口）
  - 功能：工作区与容器在远程服务器，AI 命令执行与文件读写经 SSH
  - 备注：替代 R2 中保留但未实现的 desktop gRPC 分支
  - 验收：用户可连接远程 SSH 服务器作为执行后端

- [ ] **36. 真机验证与首次发版**
  - 参考：AGENTS.md R17 发版流程
  - 涉及：真机回归清单（AI 对话 + 终端容器 + MCP / 端侧模型）三条主线；Git Tag 驱动 CI 构建 Release APK
  - 验收：真机通过三条主线验证，打 `v1.0.0` Tag 发布首个正式版

### Phase 3F：文档债（P2）

- [x] **37. 文档同步**（2026-10-25 勾选：设备控制工具描述已移除，本轮补齐导航/终端/队列/斜杠/压缩/用量/备份）
  - 现状（已修复）：`README.md` 功能列表曾列设备控制工具（observe_screen/tap_node 等），与 AGENTS.md（已移除）脱节，违反 R15
  - 实现：`README.md` 模块清单与工具集对齐当前 core（含 provider/pool/recorder/eval）；`docs/README.md` 补导航说明（左侧抽屉）与终端容器章节、消息队列与挂起、斜杠命令、上下文压缩、用量明细、备份与恢复、多提供商/多模态；`PROJECTS.md` 验证计数改为 199 项
  - 验收：文档与代码现状一致，无过时描述（本轮已核对工具集/导航/测试计数）

---

## 阶段 4：内核加固 —— 把「假设」换成「证据」

阶段 1-3 补齐了 Agent 内核与 IDE 产品形态，但内核中仍有若干「靠声明 / 假设 / 全局单例」的薄弱点：
能力靠 config 声明、验证只覆盖单个工具、降级状态全局唯一、中断恢复靠从头重跑。
本阶段把这些「假设」逐个替换为「实测 / 可验证 / 按端点粒度 / 可恢复」，
让硬实力从「声称」变为「为真」。这是「硬实力更硬」的定义，也为后续任何突破备好可复现的地基。

### Phase 4A：证据基础设施（P0）

- [x] **38. 事件录制与回放**（2026-10-11 勾选：实现已完成，commit `7be718b`；`recorder.rs` 含 `RecordingBackend` / `ReplayBackend` 与回放一致性测试）
  - 目标：任何真机上失败的任务，都能在容器内变成一条确定性 core 回归测试
  - 涉及：`events.rs`（Event 加 `v` 版本字段）；新增 `core/src/agent/recorder.rs`（一次 run 的事件流落盘）与 `replay.rs`（用录制的 `ModelBackend` 喂回 `run_loop` 做确定性重放）
  - 依赖：无（先做，作为后续方案的验证地基）
  - 验收：录制一次多步工具任务，回放产出与原 run 一致的事件序列；有 core 测试

- [x] **39. 能力探测协议化（capability 从「声明」到「事实」）**（2026-10-11 勾选：实现已完成，commit `dbf9048`；`capability.rs` 含 `CapabilityProber` trait + `ProbeCache` 缓存/衰减测试）
  - 现状：`Capabilities::from_config` 只解析静态声明，真实探测未进内核（`capability.rs`）
  - 涉及：新增 `CapabilityProbe`（探测项 + 期望 + 结果模型 + 缓存与衰减）与 `CapabilityProber` trait（平台实现真实 HTTP，符合 R6）；路由 / 压缩 / tools / vision 开关改读实测结果
  - 收益：capability / mode 分离从纸面变地基，是「防中转站」整套机制的根
  - 验收：探测结果驱动 max_tokens 钳制与 tools / vision 开关；有 core 测试（含缓存命中与衰减）

- [x] **40. 验证 oracle 泛化**（2026-10-18 勾选：实现已完成，commit `d377195` / `413d060`；`verifier.rs` 的 `VerifyPlan` 扩为 `ReadBackHash`（file_write 读回内容 sha256 哈希）+ `ExitCode`（terminal_exec 退出码断言）+ 既有 `ReadBack`（clipboard_write）；core 测试全绿）
  - 现状：`verifier::plan_for` 仅 `clipboard_write` 做 ReadBack，其余 `NoCheck`
  - 涉及：`VerifyPlan` 扩为可注册的 per-tool 后置条件（文件写 → 读回内容哈希；`terminal_exec` → 退出码 + 可选断言；MCP → 可选 schema）；工具层声明各自验证器
  - 收益：把「不假设成功」（R15）从单点变系统属性
  - 验收：file_write / terminal_exec 验证失败能回填原因并标记失败；有 core 测试

- [x] **41. 平台契约测试套件**（2026-10-11 勾选：实现已完成，commit `b9711b8`；`core/tests/conformance.rs` 7 项契约用例覆盖六个平台 trait）
  - 现状：六个平台 trait（`ActionExecutor` / `ModelBackend` / `McpClient` / `MemoryStore` / `FileAccess` / `Approver`）无「任何实现都必须通过」的契约用例
  - 涉及：新增 `conformance` 测试模块 + mock 平台，覆盖全部六个 trait
  - 收益：跨端实现不漂移；新增平台（桌面 / CLI / 远程）的准入证
  - 验收：mock 平台通过全部契约用例；文档说明如何用其校验新平台实现

### Phase 4B：结构性壁垒（P1）

- [x] **42. 端点池与质量感知路由**（2026-10-18 落地：新增 `core/src/agent/pool.rs`——`EndpointHealth`（EMA 成功率 score/ successes/failures，初值 1.0）+ `EndpointProfile { id: normalize_endpoint_id(base_url), kind, capabilities, health }`（`Capabilities`/`Backend` 加 serde derive）+ `EndpointPool { endpoints, current }`：质量感知选路 `select`（过滤可用：kind↔hints + health>0.05 → 打分：health + 复杂度匹配（Hard 偏好云端/桌面、Simple 偏好端侧）→ 最高分）+ 故障切换 `switch_away_from`（降权失败端点 + 排除选下一个，无替代返回 None 不静默）+ `note_result`（成功/失败回写 EMA）+ 快照往返（「降级快照含池健康」：`snapshot`/`from_snapshot`）；`RunConfig.endpoints`（非空启用池路由，空走原 route()）；`Event::EndpointSelected{id,index}` / `Event::EndpointSwitched{from,to}`（选路/切换决策可观测）；run_loop 集成：每轮池选路（选中端点 kind 作 backend）、生成失败时 switch_away_from + 事件 + `continue` 重试下一轮、成功 note_result(true)、池无可用端点显式失败；`jni/lib.rs` 解析 `config.endpoints`（`[{baseUrl, kind}]`）；子代理沿用父端点池、canary 不入池；run_loop 集成测试：故障切换链路 + 无可用显式失败；core `#[test]` 计数 183）
  - 现状：`Backend` 仅 4 个固定枚举、`core.backend_mut()` 单后端、`DegradeState` 全局唯一（`router.rs` / `mod.rs`）；`degrade.rs` 已声明「降级模式＝端点池零号档」，但端点池未实现
  - 涉及：新增 `core/src/agent/pool.rs`（`EndpointProfile { id, kind, capabilities, health }` + `EndpointPool`）；`DegradeState` 下沉为 per-endpoint；路由输入从 `RouteHints` 扩为「池视图」，选端点＝过滤可用 + 按 health / 复杂度 / 成本 打分；`RunConfig` 接收端点列表；新增 `Event::EndpointSelected` / `EndpointSwitched`；降级快照含池健康
  - 收益：真正的多端点 failover + 按质量选端点（同类无此设计）
  - 验收：多端点配置下故障自动切换、按 health 路由；有 core 测试（切换 / 打分 / 快照往返）
  - **配套说明（与 25 同一件事两半）**：同 kind 多后端实例注册（`AgentCore.backends` 多 CloudBackend per base_url）留待 25 多模型提供商接线；本轮池在「选路决策/健康/切换」层完整落地，实际生成仍经现有单实例 per-kind 后端

- [x] **43. 可恢复执行状态机**（2026-10-18 落地：core 新增 `run.rs::LoopState`（`stage: LoopStage{generate,tools}` + `step` + `messages` + `pending_calls`/`pending_cursor` + degrade 快照，可序列化，旧版缺字段 serde default 兼容）+ `Event::LoopState`（平台落盘）+ `RunConfig.loop_state`（恢复注入）；恢复语义：stage=tools 从 `pending_cursor` 续跑未执行部分（已完成工具不重复）、stage=generate 重跑本步；存档时机：每个工具调用执行后发一档、后台中断前发一档（stage=generate）；`run_loop` 恢复轮跳过重新生成/观测/补偿/assistant 重复入栈（`resumed` 标志），直接消费检查点 tool_calls；子代理不继承父 loop_state（`subagent.rs` `loop_state: None`）；Kotlin `AgentHost` 落盘 `agent_state/loop_state`、`run()` 注入 `loopState`、`done` 时清除断点（降级快照保留）；AGENTS.md R13 已更新（检查点从消息快照升级为循环光标，2026-10-18 经用户授权）；core `#[test]` 计数 174）
  - 现状：切后台仅 `break`，恢复靠检查点从头重跑（`run.rs`；R13：core 无状态）
  - 涉及：core 暴露可序列化的 `LoopState`（messages + 步数 + 待执行 tool_calls 游标 + degrade 快照）；检查点从「消息快照」升级为「循环光标」；恢复时从中断处续跑且已执行工具不重复
  - 规则影响：与 **R13（core 不持久化）冲突**，需先更新 R13 并征得用户同意
  - 收益：手机随时被杀也无损续跑（移动端强需求）
  - 验收：中断后恢复不重复执行已完成的工具调用；有 core 测试（含游标序列化往返）

### Phase 4C：行为环（健康环的并列维度，独立于降级状态机）

- [x] **4C-0. 健康环（补登记）**
  - 现状：`degrade.rs`（降级状态机：Full / NoTools / BareText + 五类失败信号 + 指数退避重探）+ `signals.rs`（环境信号槽）为 commit 2 已落地，但此前 ROADMAP 从未立目
  - 登记为正式任务；与行为环（4C-2 / 4C-3）并列，构成可靠性双环

- [x] **4C-1. 网络+协议层（1a，补登记）**
  - 现状：commit 1 已落地——`pick_timeout` / `backoff_delay` / `classify_status`（含预留第四类）/ content-type 白名单 + 200 但非法判协议错 / tool_call arguments JSON 完整性校验（`cloud.rs` / `openai.rs`）
  - 备注：事件格式（FailureKind 四类）属 events.rs 决策事件 schema 一部分，45 前置依赖引用

- [x] **4C-2. 行为观测层（1b）**（2026-10-18 落地：core `#[test]` 计数 158；`openai.rs::validate_call_against_schema`（schema 子集：required / properties 类型 / additionalProperties:false / minimum-maximum，纯函数）；`run.rs::ActivityTracker`（纯函数 `observe(events, history) -> ActivitySignal`，绝对信号强动词+零调用始终启用、相对信号意向词+零调用且端点历史 ≥20 轮+有调用基线启用；observed 窗口仅运行时构建，不进检查点；观测异常发 `Event::Notice` 带 `tag`——`behavior:schema:mismatch` / `behavior:idle:abs` / `behavior:idle:rel`）；`degrade.rs::normalize_endpoint_id`（纯函数带单测，scheme+host小写+完整 path、去 trailing slash/query/fragment、宁分勿合）；`DegradeState` 扩展 `behavior_dim: BehaviorDim{Normal/Boosted/Forced}`（默认 Normal）+ `behavior_history: Map<endpoint_id, BehaviorHistory>`（含 `forced_failure_count` / `last_tool_call` / `strong_verb_hits` / `intent_hits`，快照追加在数组尾部索引 5/6，旧快照 5 项兼容回退默认）；`events.rs::Event::Notice` 加 `tag: Option<String>`（serde default + skip_serializing_if，旧录制可反序列化）+ `enum NoticeTag`（`as_str` 转规范字符串）；JNI `RunConfig.base_url` 传入（`normalize_endpoint_id` 归一化为桶键，空串归 "default"）。验收：schema 校验失败与活跃度异常可观测；有 core 测试）
  - 实现：`openai.rs` 新增 `validate_call_against_schema(spec, args)`（P0.1 参数校验）；`run.rs` 新增 `ActivityTracker{strong_verbs, intent_phrases, history_window=50}`（**纯函数** `observe(events) -> Signal`，重放可从事件流重建观测，运行时状态不进检查点）；绝对信号（强动词 + 无 tool_call）始终启用，相对信号（端点自历史）冷启动 20 轮后启用；活跃度异常写 `Event::Notice`（仅观测不补偿）
  - `DegradeState` 扩展 `behavior_history: Map<endpoint_id, BehaviorHistory>`（per-endpoint 键控；endpoint_id = `normalize_endpoint_id(base_url)`，1b 落地时在 core 写纯函数带单测，42 的 `EndpointProfile.id` 与 43 序列化边界引用）
  - 规范化规则：scheme + host（小写）+ 完整 path，去 trailing slash / query / fragment，**宁分勿合**（分裂只分桶细，合并会导致不同端点行为历史互相污染）
  - `Event::Notice` 加 `tag: Option<String>`（`#[serde(default, skip_serializing_if = "Option::is_none")]`，旧录制可反序列化；core 内部用 `enum NoticeTag`，序列化层转规范字符串如 `behavior:L3:forced`）
  - 验收：schema 校验失败与活跃度异常可观测；有 core 测试

- [x] **4C-3. 行为补偿层（1c）**（2026-10-18 落地：`GenerateRequest.tool_choice`（`request_body` 按其覆盖，默认 auto）；L1 元信号三级位置轮换（`run.rs::inject_meta_signal`：system 开头 → 最后一条 user 前缀 → system 尾部，仅改请求消息不改持久 messages，轮换游标 meta_pos 运行时状态不进检查点）；L2 = Boosted 档下显式 `tool_choice=auto`（tag `behavior:L2:auto` 在 L1Injected Notice 中呈现）；L3 = Forced 档下一轮 `tool_choice=required`（generate 后立即回落 Boosted，防锁死）；行为环升降档 `DegradeState::escalate_behavior`/`deescalate_behavior`（异常升一档、一次成功调用降一档并清零 forced 计数+仲裁冷却；守卫：健康环关 tools 时冻结不升降档；成功判定加 `round_schema_failed` 排除失败调用误清零）；强制轮失败独立计数 `behavior_history.forced_failure_count`（不进健康环 ToolSchema 连续计数）≥3 触发 canary 仲裁（`run.rs::behavior_canary`：单轮探测端点能否调用工具；通过→`canary_cooling=true` 仲裁冷却不再重复触发、计数继续累计；失败→健康环 `note_failure(ToolSchema)` + 行为环复位+清零）；补偿动作复用 `Notice.tag`（`behavior:L1:injected`/`behavior:L2:auto`/`behavior:L3:forced`/`behavior:L3:recovered`/`behavior:L3:canary`，未新增事件类型）；`BehaviorHistory.canary_cooling` 进快照（serde default 兼容）；升档在观测块后（无工具调用的异常轮会提前 Done 返回，跨任务经快照延续到 L3），降档/仲裁在工具循环后；core `#[test]` 计数 171）
  - 实现：L1 元信号三级切换（system → user 前缀 → system prompt 尾部）；L2 检测命中后 `tool_choice: "auto"`；L3 命中 + 强动词 → `tool_choice: "required"`（仅一轮）；守卫：`capability.NoTools` 时 behavior 冻结且丢计数
  - `DegradeState` 扩展 `behavior_dim: Normal / Boosted / Forced`（1b 落地时一次定义好，`#[serde(default)]` 默认 Normal；43 序列化边界按最终字段集设计）
  - **强制轮失败独立计数**：L3 强制轮 schema 失败 → `behavior_history.forced_failure_count`（连续堆叠），**不进入** degrade.rs 的 ToolSchema 连续计数；`>= 3` 触发一次 canary 仲裁探测；canary 失败 → 健康环降级（客观证据）；canary 通过 → 进入**仲裁冷却**（同端点不再重复触发 canary，计数继续累计直到一次成功清零或行为环降级）；一次成功调用即清零（跟随 degrade「成功清零」语义），Forced → Boosted → Normal
  - 补偿动作复用 `Event::Notice.tag`（不新增事件类型，符合「1c 不改 schema」）；tag 结构化 `behavior:{layer}:{action}`（如 `behavior:L3:forced` / `behavior:L3:recovered` / `behavior:L1:injected`）
  - 验收：检测命中后请求构造变化可观测（tag）；有 core 测试

### Phase 4D：生命周期与评测（P0）

- [x] **44. 任务生命周期**（2026-10-18 落地：Android 侧——`AgentForegroundService` 声明 `foregroundServiceType="dataSync"` 与 `FOREGROUND_SERVICE_DATA_SYNC` 权限（Manifest 已有）；新增 `Service.onTimeout(startId, fgsType)` 无条件 `stopSelf`（API 33+，此路径无任何阻塞操作，符合 Android 15 dataSync 24h 累积 6h 约束）；前台服务随任务启停：`MainActivity.startAgent` 成功时启动、任务线程结束时停止（`startForegroundService` 失败不阻断任务——保活只是加速器，执行与保活解耦）；OnSuspend = `onStop` 置 app_background 信号（core 检查点存档后 break，已有）/ OnResume = `onStart` 清信号（已有）/ 冷启动 = `loadRecentSession` 恢复 UI + 下次 run 注入 `loop_state` 断点续跑（43 已接线）；push-resume 接口位 = `Event::LoopState` + `RunConfig.loop_state`（恢复入口已预留，FCM+后端第一版不实现）；验收：core 游标序列化往返/恢复不重复执行由任务 43 测试覆盖（`loop_state_json_roundtrip_with_cursor`/`resume_skips_already_completed_tools`/`resume_generate_stage_replays_step`）；Kotlin/平台侧容器无法编译验证，UNVERIFIED 等 CI `android-release.yml`）
  - 约束（已对照 Android 15 官方文档验证）：dataSync 前台服务为 **24 小时内累积 6 小时**（用户无交互持续累积）；`Service.onTimeout(int,int)` 必须几秒内 `stopSelf()`（否则系统抛 `RemoteServiceException`）；6 小时耗尽后除非用户切到前台否则无法再启动 dataSync（`ForegroundServiceStartNotAllowedException`）；用户切到前台可重置计时器
  - 设计：**执行与保活解耦**——任务状态在检查点（任务 43），前台服务只是当前活跃窗口的加速器；`onTimeout` 无条件 `stopSelf()` 并触发检查点保存（此路径不做任何可阻塞操作）
  - 生命周期钩子：`OnSuspend`（`onStop` 触发，几秒内序列化）/ `OnResume`（进程还活着回前台，刷新 token / 重建连接 / 重挂 UI）/ 冷启动（进程已死，检查持久化状态，有则恢复、无则新建）
  - 产品语义：任务卡暂停按钮 = OnSuspend；继续 = OnResume 或冷启动恢复
  - **push-resume 接口位**：FCM + 后端协调（云端完成后推送携带检查点的 payload，设备恢复呈现）——第一版不实现，但检查点设计预留恢复入口 / 状态源标记，不得堵死此路径
  - 依赖：任务 43（检查点机制）
  - 验收：任务中途进程被杀 / 服务超时后，重新进入可从检查点恢复继续；有 core 测试（游标序列化往返）

- [x] **45. Agent 能力评测基准**（2026-10-18 落地：新增 `core/src/agent/eval.rs`——`EvalReport` / `EvalSummary` / `evaluate` / `report_from`；指标：工具调用准确率（tool_success / tool_calls）/ 多步任务完成率（以 Done 收尾）/ 上下文保持（重放一致性代理 `replay_consistent`）/ 失败恢复率（restored / degraded）；行为环聚合按 `Notice.tag` 的 `behavior:` 前缀统计；复用 `recorder.rs::ReplayBackend` 确定性重放，CI 可跑；`evaluate` 接收 `Box<dyn ActionExecutor>` 注入（mock 或真实平台）；core `#[test]` 计数 163）
  - 实现：复用 `recorder.rs` 的 `ReplayBackend` 作为确定性测试集；定义核心指标：工具调用准确率 / 多步任务完成率 / 上下文保持 / 失败恢复率；CI 可跑
  - **前置依赖**：4C-2（1b）落地且 events.rs 决策事件 schema 冻结；4C-1 已落地不阻塞；4C-3（1c）不在依赖内（不新增事件类型，补偿复用 `Notice.tag`）；commit 2 / 3 不在依赖内
  - 行为环评测：按 `Notice.tag` 的 `behavior:` 前缀聚合重放流，判定 L1 / L2 / L3 是否按预期触发
  - 验收：录制一次多步工具任务，回放产出与原 run 一致；有 core 测试

---

## 优先级

| 等级 | 含义 | 任务 |
|---|---|---|
| **P0** | 必备（做这个 Agent 才像 Agent） | 1, 2, 3, 4, 6 |
| **P1** | 重要（能规模化使用） | 5, 7, 8, 9 |
| **P2** | 完善 | 10, 11, 12 |
| **P3** | 顶尖的顶尖 | 13, 15, 16, 17, 19 |
| **P4** | 长期 | 14, 18, 20 |
| **P0** | 工作台三件套（追平 AiCode 产品形态的基石） | 22, 23, 24 |
| **P0** | 核心能力接线（多模型 + 多模态） | 25, 26 |
| **P1** | 交互体验（27 任务队列 / 28 斜杠命令 / 30 用量，表述按 46 决策） | 27, 28, 30 |
| **P2** | 上下文压缩增强（降级） | 29 |
| **P1** | 端侧与并行 | 31（降级：仅编译验证 + 模型路径接入）, 32 |
| **P2** | 扩展与成熟度 | 33, 34, 35, 36 |
| **P2** | 文档债 | 37 |
| **P0** | 内核证据基础设施（录制回放 / 探测 / 验证 / 契约） | 38, 39, 40, 41 |
| **P0** | 内核结构性壁垒（端点池 / 可恢复） | 42, 43 |
| **P0** | 行为环（健康环与 1a 已落地补登记；1b 观测 / 1c 补偿） | 4C-0/1, 4C-2, 4C-3 |
| **P0** | 移动端任务生命周期 | 44 |
| **P0** | 评测基准（顶尖的可证伪定义，P0 首位） | 45 |

## 关键依赖

- 任务 **6**（持久化）→ **8**、**9**、**10** 的前置
- 任务 **3**（授权策略）→ **1**（模式）的前置
- 任务 **4**（Token 统计）→ **17**（评估指标）的前置
- 任务 **5**（原因码）→ **18**（复盘工具）的前置
- 任务 **13**（能力探测）→ **14**（金丝雀）的前置
- 任务 **22**（文件编辑器）→ **23**（Git diff 展示）的前置
- 任务 **22**（侧边栏）→ **24**（多会话列表）的前置
- 任务 **29**（压缩增强）→ **28**（`/compress` 命令）的前置
- 任务 **31**（端侧推理）→ R4 状态从 UNVERIFIED 转 VERIFIED 的前置
- 任务 **38**（录制回放）→ 39-43 的验证地基（先做）
- 任务 **39**（能力探测）→ **42**（端点池按 health 路由）的前置
- 任务 **42**（端点池）→ **43**（可恢复快照含池健康）的参考
- 任务 **43**（可恢复）→ 需先更新 **R13**
- 任务 **45**（评测基准）→ 依赖 4C-2（1b）落地且 events.rs 决策事件 schema 冻结；4C-1 已落地不阻塞；4C-3 不在依赖内（不新增事件类型，补偿复用 `Notice.tag`）；commit 2/3 不在依赖内
- 任务 **43**（可恢复）→ 序列化边界依赖 4C-2 的 `behavior_history` 最终形态（`Map<endpoint_id, BehaviorHistory>`，按 1c 字段集设计）
- 任务 **42**（端点池）→ 依赖 4C-2 的 `normalize_endpoint_id` 函数（`EndpointProfile.id` 引用）；与任务 **25**（多模型提供商）配套，同一件事两半
- 任务 **44**（任务生命周期）→ 依赖任务 **43**（检查点机制）

## 建议实施节奏

| Batch | 周期 | 内容 |
|---|---|---|
| **1** | 1-2 周 | 3 → 1 → 5 → 4（信任工程基础设施） |
| **2** | 2-3 周 | 6 → 7 → 2（持久化 + 撤销） |
| **3** | 3-4 周 | 8 → 9 → 10（能力扩展） |
| **4** | 1-2 周 | 11、12（工程质量） |
| **5** | 长期 | 13-20（按需推进） |
| **6** | 2-3 周 | 37（文档债）→ 22 → 23 → 24（工作台三件套） |
| **7** | 2-3 周 | 25 → 26（多模型 + 多模态接线） |
| **8** | 2-3 周 | 27 → 28 → 30（交互体验，按 46 决策重新表述；29 压缩降 P2 另行推进） |
| **9** | 3-4 周 | 32 → 31（子代理并行先行；31 降级为编译验证 + 模型路径接入） |
| **10** | 长期 | 33、34、35、36（扩展与发版） |
| **11** | 1-2 周 | 38 → 39 → 40 → 41 → 4C-2（内核证据基础设施 + 行为观测；4C-0/1 登记） |
| **12** | 2-3 周 | 45 → 4C-3 → 43 → 44（评测基准 → 行为补偿 → 可恢复 → 任务生命周期） |
| **13** | 1-2 周 | 42（端点池，依赖 4C-2 的 id 函数，与 25 配套） |

**里程碑**：Batch 1-2 完成后 yaya_ai 「能用了」；Batch 1-4 完成后「能规模化使用」；Batch 5 完成后才叫顶尖；
**Batch 6-7** 完成后「从聊天壳变成 IDE 工作台」；**Batch 8-9** 完成后「核心能力真机可用」；**Batch 10** 完成后「正式发版」；
**Batch 11-13** 完成后「内核里的假设换成证据、可靠性双环（健康 + 行为）、任务可恢复、端点池与评测基准就位」——顶尖定位的完整证据链。

---

## 更新记录

- **2026-10-31**：任务 32 落地（子代理真并行与自定义）——core：`SubagentDef` 自定义定义 + 内置 `explore` 只读代理 + `RunConfig.tool_allowlist`（只收窄）+ `max_parallel_subagents` + `SubagentFork` 工厂 + `run_subagents_parallel`（scoped 线程、分批、事件按下标确定性重放、无工厂回退顺序）+ `Event::Subagent`；`run.rs` 每轮预派发 subagent 并回填结果，白名单外调用显式拒绝。JNI：`JniSubagentFork` 重建后端与平台 trait、子线程 daemon 挂载、`config.subagents`/`maxParallelSubagents`。Compose：`Subagents.kt` 存储、设置页管理与并行上限、任务卡子代理状态 chip + 子会话弹窗、授权队列锁。core `#[test]` 209（含并发屏障测试与白名单/定义解析测试）+ 契约 7；三 ABI `.so`、`assembleDebug`、`testDebugUnitTest` 通过；Kotlin/Compose UNVERIFIED。

- **2026-10-03**：初版建立，20 任务清单。基于「向 AiCode 靠齐 → 超越 AiCode」两阶段规划。
- **2026-10-03**：Batch 1 落地 —— 任务 1/3/4/5（core + JNI + Kotlin + Dart）。core 61 测试全过、JNI 编译检查通过；Android UI 侧未真机验证。任务 4 的 UI 仅显示总量，拆分/费用估算留待任务 17。
- **2026-10-03**：Batch 2 落地 —— 任务 2/6/7（SQLite 持久化 + 检查点撤销 + 迁移框架）。AGENTS.md 新增 R12（运行模式与授权）/ R13（本地持久化）。core 61 测试全过；Android/Dart 未真机验证。
- **2026-10-03**：Batch 3 落地 —— 任务 8/9/10（子代理顺序递归 + 技能系统 + 自动记忆）。core 73 测试全过；跨端零改动（事件流覆盖）。任务 8 为顺序执行，真并行留待后续。
- **2026-10-03**：Batch 4 落地 —— 任务 11/12（轻量 i18n + 用户文档）。Dart 侧无容器验证；任务 11 覆盖聊天页，其余页面留待增量。
- **2026-10-03**：Batch 5 落地 —— 任务 13/14/15/16/19/20（能力探测 + 金丝雀 + 注入防御 + MCP 白名单 + 决策原则深化 + 环境验证 oracle）。core 85 测试全过。
- **2026-10-03**：Batch 6 落地 —— 任务 17/18（评估面板 + 决策复盘）。Kotlin 统计聚合 + usage/policy 事件入库 + Dart 统计弹窗 + policy 消息展示。**ROADMAP 20 个任务全部完成。**
- **2026-10-03**：Batch 7 落地 —— 任务 21（工作区文件系统，新增）。core 文件工具 + Kotlin 路径安全访问 + JNI + Dart 浏览入口。core 88 测试全过。
- **2026-10-03**：阶段 3 规划建立 —— 任务 22-37（16 项）。基于与 AiCode 的逐项对比，补齐 IDE 工作台形态（文件编辑器/Git/多会话/多模型）与核心能力接线（端侧推理落地/多模态激活/子代理并行），以及文档同步与真机发版。阶段 3 完成后 yaya_ai 从「内核 + 聊天壳」演进为「内核 + IDE 工作台」。
- **2026-10-04**：阶段 4 规划建立 —— 任务 38-43（6 项，内核加固）。主线是「把内核里的假设替换为证据」：事件录制回放（38）、能力探测协议化（39）、验证 oracle 泛化（40）、平台契约测试（41）、端点池与质量感知路由（42）、可恢复执行状态机（43）。任务 43 需先更新 R13。任务 38 为其余任务的验证地基，建议先做。
- **2026-10-11**：阶段 4 收尾与结构调整 —— 任务 38/39/41 勾选（实现此前已提交未勾选）；新增产品形态决策 46（主界面 = 聊天 + 任务卡，终态 = 任务控制台愿景）、任务 44（任务生命周期，dataSync 6h 累积 / onTimeout stopSelf / 执行与保活解耦 / push-resume 留接口位）、任务 45（Agent 能力评测基准，复用 ReplayBackend）；新增阶段 4C 行为环（4C-0 健康环、4C-1 网络+协议层补登记，4C-2 行为观测、4C-3 行为补偿）；42/43 升 P0；29 降 P2、31 降级（编译验证 + 模型路径接入）；27/28/30 按 46 决策重新表述。同步 AGENTS.md R6/R8/R17 与 PROJECTS.md 漂移修正。core `#[test]` 计数 143。
- **2026-10-18**：任务 40 补勾选（实现已完成 commit `d377195`/`413d060`，此前未勾选）——验证 oracle 泛化落地（`ReadBackHash` file_write 哈希 + `ExitCode` terminal 退出码断言，core 测试全绿）。经评估，下一步优先推进内核证据链 4C-2 → 4C-3 → 43 → 44 → 45 → 42（纯 core 可测、任务控制台愿景前置）。
- **2026-10-18**：任务 4C-2 落地（行为观测层）——`openai.rs` 加 `validate_call_against_schema`；`degrade.rs` 加 `normalize_endpoint_id` / `BehaviorDim` / `BehaviorHistory`（快照尾部追加，旧 5 项快照兼容）；`events.rs` 的 `Notice` 加 `tag` + `enum NoticeTag`；`run.rs` 加 `ActivityTracker` 观测（observed 运行时窗，纯函数，不进检查点）+ schema 校验 Notice；`subagent.rs`/`canary.rs`/`jni/lib.rs` 随 RunConfig 加 `base_url` 字段；`mod.rs` re-export。core `#[test]` 计数 158（含 `core/tests/conformance.rs` 7 项契约）。
- **2026-10-18**：任务 45 落地（Agent 能力评测基准）——新增 `core/src/agent/eval.rs`（`EvalReport`/`EvalSummary`/`evaluate`/`report_from`；指标：工具调用准确率/多步任务完成率/上下文保持（重放一致性）/失败恢复率；行为环按 `behavior:` 前缀聚合 `Notice.tag`）；复用 `ReplayBackend` 确定性重放，CI 可跑；`mod.rs` 加 `pub mod eval`。core `#[test]` 计数 163。
- **2026-10-18**：任务 4C-3 落地（行为补偿层）——`GenerateRequest.tool_choice` + `request_body` 覆盖；L1 元信号三级位置轮换（`inject_meta_signal`）；L3 `required` 仅一轮（generate 后回落 Boosted）；行为环升降档 + 成功降档清零（`round_schema_failed` 排除失败调用）；`forced_failure_count` 独立计数 ≥3 → canary 仲裁（通过→`canary_cooling` 冷却；失败→健康环降级+行为环复位）；`Notice.tag` 新增 `behavior:L1:injected`/`L2:auto`/`L3:forced`/`L3:recovered`/`L3:canary`（未新增事件类型）；`BehaviorHistory.canary_cooling` 进快照。core `#[test]` 计数 171。行为环（健康环 + 1a/1b/1c 双环）至此完整落地。
- **2026-10-18**：任务 43 落地（可恢复执行状态机，R13 已授权更新）——core 新增 `LoopState`/`LoopStage` + `Event::LoopState` + `RunConfig.loop_state`；工具执行后/后台中断前存档；恢复轮跳过重新生成直接续跑未完成 tool_calls（已完成不重复）；子代理不继承父断点；Kotlin `AgentHost` 落盘/注入/`done` 清除；AGENTS.md R13 更新（循环光标取代消息快照续跑，回滚检查点保留）。core `#[test]` 计数 174。
- **2026-10-18**：任务 44 落地（任务生命周期）——`AgentForegroundService.onTimeout` 无条件 `stopSelf`（Android 15 dataSync 6h 累积约束）；前台服务随任务启停（`startAgent` 成功启动/线程结束停止，启动失败不阻断）；OnSuspend/OnResume/冷启动钩子（app_background 信号 + loop_state 续跑，43 已接线）；Manifest dataSync 类型与权限（已有）。验收由 43 游标测试覆盖；Kotlin 侧 UNVERIFIED 等 CI。
- **2026-10-18**：任务 42 落地（端点池与质量感知路由）——新增 `core/src/agent/pool.rs`（`EndpointHealth` EMA / `EndpointProfile` / `EndpointPool` select 打分 + `switch_away_from` 故障切换 + 快照往返）；`RunConfig.endpoints` 池路由；`Event::EndpointSelected`/`EndpointSwitched`；run_loop 集成（选路/切换重试/健康回写/无可用显式失败）；`jni/lib.rs` 解析 `config.endpoints`；`Capabilities`/`Backend` 加 serde derive。core `#[test]` 计数 183。**内核证据链 4C-0/1/2/3 + 38/39/40/41/42/43/44/45 全部落地**；同 kind 多后端实例注册留待 25 配套。
- **2026-10-18**：任务 25 内核部分落地（多模型提供商，UI/设置侧待续）——新增 `core/src/agent/provider.rs`（`ApiProtocol{OpenAi/Anthropic/Gemini}` + `ApiRequest`/`AuthStyle` + `SseParser` 统一接口；三协议请求构造（Anthropic `/v1/messages` system/tool_use/tool_result 合并、Gemini `:streamGenerateContent` contents/functionCall/functionResponse）+ `AnthropicAccumulator`/`GeminiAccumulator` SSE 解析器（text/工具调用/usage，错误事件上抛）；`cloud.rs` `CloudBackend::new_with_protocol` + generate 三路分派（传输防御层复用）；`AgentCore.register_endpoint`/`endpoint_backend_mut` 多端点实例 + `effective_hints` 纳入实例可用性；`run.rs::pick_backend`（池选中端点优先独立实例、回退 per-kind）+ `selected_endpoint`；`openai::ResponseAccumulator` 适配 `SseParser`（mem::take 兼容消费型 finish）；jni：`config.providers`（[{baseUrl,apiKey,model,type}]）注册多 CloudBackend 实例 + 池派生（缺省回退旧单端点/`endpoints` 字段）；测试：三协议请求形状/双协议解析/多实例路由。core `#[test]` 计数 190。**待续**：Compose 多提供商配置界面 / 模型选择弹窗 / Kotlin config 下发 providers（UI 侧，容器无法验证）。
- **2026-10-25**：任务 33 落地（终端多标签与辅助按键）——`TerminalScreen.kt` 重写为多标签（每个标签独立回滚缓冲/历史，最多 5 个，可新建/关闭/切换）+ 辅助按键栏（Esc/Tab/Ctrl-C/←→/↑↓/常用符号）+ 字号（9–22）与 3 档墨色配色（持久化）+ 只读「AI」标签；新增 `TerminalLog.kt`（最多 20 次命令 × 2000 行，StateFlow 版本号驱动 UI）；`ProotManager.runCommandBlockingWithCode` 标记 `lineSource=agent` 并写日志（Git 等同步命令仍归 ui），pump 过滤哨兵行；`TerminalLogTest` 5 项单测 + `assembleDebug` 通过，Compose 侧 UNVERIFIED。限制：单容器串行执行；光标样式未做。
- **2026-10-25**：任务 37 落地（文档同步）——`docs/README.md` 补「左侧抽屉」导航说明、终端容器章节（单容器串行、workspace bind、git 前置）、消息队列与挂起、斜杠命令、上下文摘要压缩、用量明细、备份与恢复；修正「文件 tab」等旧导航措辞；工具集/测试计数与 core 一致（`PROJECTS.md` 199 项）。
- **2026-10-25**：任务 27/28/29/30 收口勾选（实现此前已在工作区落地，本轮核验并同步描述）——27 消息队列→任务队列（排队/逐项移除/自动发送/停止=挂起并存档 `LoopState` 发 `Suspended`，`resumeAgent` 续跑）；28 `/status` 斜杠命令（忙时排队，`/compress` 按 46 决策暂缓）；29 上下文摘要压缩（`compact_with_summary` + `compact_with_backend`：模型摘要、失败/无后端回退截断并 `Notice`、用量 `kind="compact"`）；30 用量透明度（`Usage` 扩 cached/duration/kind，DB v4 加明细列，气泡下展示拆分/命中率/耗时，状态行累计费用）。core `#[test]` 192 + 契约 7；Android 单测与 `assembleDebug` 通过；Compose 侧 UNVERIFIED。
- **2026-10-25**：任务 34 落地（备份与恢复）——新增 `android/.../BackupFormat.kt`（纯 JVM：`YAYABAK1` 容器头 + PBKDF2-HMAC-SHA1 派生 256 位密钥 + AES-256-GCM；zip 打包/解包含 zip-slip 与解压总量防护）、`BackupPrefs.kt`（SharedPreferences XML → 键值，支持 string/boolean/int/long/float/string-set，未知类型显式上报）、`BackupHost.kt`（导出/恢复 `shared_prefs` + `files/workspace` + `files/skills`，恢复经 SharedPreferences API 逐键写回、工作区合并覆盖、不恢复 `agent_state` 任务断点）、`ui/BackupScreen.kt` + 抽屉「备份」入口（SAF 选文件，无需存储权限）；`build.gradle` 加 `testImplementation junit`，`app/src/test` 6 项 JVM 单测（加密往返 / 错误密码 / 短密码 / 非备份文件 / zip-slip / 配置类型解析）全绿；`assembleDebug` 通过。不含：会话与记忆数据库、rootfs 镜像、工作区同步（SFTP/FTP）；Kotlin/Compose 侧 UNVERIFIED（无真机）。
- **2026-10-25**：任务 25 UI 收尾 + 任务 26 多模态落地——任务 25 补 `Providers.kt`/设置页提供商管理与能力标签/对话页模型切换弹层/config 多提供商下发/三协议模型拉取（Kotlin/Compose UNVERIFIED）。任务 26：core 首条 user 消息图片（三协议序列化 + run/provider 测试）、JNI `userImages` 白名单校验、Compose 相册多选与端侧 JPEG 压缩、待发送缩略图、图片消息与助手 Markdown 图片内联、全屏双指/双击缩放、失败可重试；消息表 v3 `images` 列 + `filesDir/chat_images/` 受管文件随会话/检查点恢复，删会话/回滚清理孤儿；模型 `image` 能力关闭时拦截；本地（llama）后端多模态路由留待任务 31。顺手消除 `gen_duration_ms` 未使用赋值告警。core `#[test]` 计数 199（含 conformance 7）；`cargo check -p yaya-core-jni --target aarch64-linux-android --no-default-features`、三 ABI `.so` 交叉编译、`assembleDebug` 均通过；Kotlin/Compose 侧 UNVERIFIED（无真机，未宣称已验证）。
