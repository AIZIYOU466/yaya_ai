# YAYai - 开源 Android AI Agent 客户端

YAYai 是一个开源的 Android AI Agent 客户端，支持配置厂商 AI 模型、调用 API 聊天、内置终端和 MCP 服务。

## 功能特性

- **AI 聊天**: 支持任意 OpenAI 兼容的 API 接口
- **流式响应**: 实时显示 AI 回复
- **工具调用**: AI 可以调用内置工具执行任务
- **终端容器**: 基于 proot 的 Debian 容器环境
- **MCP 服务**: 支持 Model Context Protocol 插件
- **高级 UI**: 毛玻璃质感、蒙版动画

## 技术栈

- **UI**: Flutter + Riverpod
- **网络**: Dio
- **容器**: proot + Debian rootfs
- **MCP**: Model Context Protocol
- **状态管理**: Riverpod

## 快速开始

### 1. 安装依赖

```bash
flutter pub get
```

### 2. 配置 AI 提供商

1. 打开应用，进入「设置」
2. 配置以下信息：
   - **Base URL**: AI API 的基础 URL（如 `https://api.openai.com/v1`）
   - **API Key**: 你的 API 密钥
   - **Model Name**: 模型名称（如 `gpt-3.5-turbo`）
   - **流式响应**: 是否启用流式输出

### 3. 启动终端容器

1. 进入「终端」页面
2. 点击「启动」按钮
3. 首次使用会自动下载 Debian rootfs（约 30MB）

### 4. 配置 MCP 服务

1. 进入「MCP」页面
2. 添加 MCP 服务器配置
3. 支持 stdio 和 HTTP 两种类型

## 项目结构

```
yaya_ai/
├── lib/
│   ├── main.dart              # 应用入口
│   ├── models.dart            # 数据模型
│   ├── providers.dart         # 状态管理
│   ├── services/              # 业务服务
│   │   ├── ai_service.dart    # AI API 服务
│   │   ├── terminal_manager.dart  # 终端容器管理
│   │   └── mcp_manager.dart   # MCP 服务管理
│   ├── screens/               # UI 屏幕
│   │   ├── chat_screen.dart   # 聊天界面
│   │   ├── config_screen.dart # 配置界面
│   │   ├── terminal_screen.dart # 终端界面
│   │   └── mcp_screen.dart    # MCP 管理界面
│   └── widgets/               # UI 组件
│       └── glass_card.dart    # 毛玻璃组件
├── android/                   # Android 原生配置
└── pubspec.yaml               # 依赖配置
```

## 依赖说明

- `flutter_riverpod`: 状态管理
- `dio`: HTTP 客户端
- `path_provider`: 路径获取
- `permission_handler`: 权限管理
- `go_router`: 路由管理
- `shared_preferences`: 配置存储

## 构建

```bash
# 构建 APK
flutter build apk --release

# 构建 App Bundle
flutter build appbundle --release
```

## 参考项目

- [AiCode](https://github.com/ankidroid/AiCode): Android 代码编辑器
- [Operit AI](https://github.com/AAswordman/Operit): Android AI Agent
- [RikkaHub](https://github.com/rikkahub/rikkahub): Android LLM 客户端

## 许可证

MIT License
