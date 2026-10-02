import 'package:equatable/equatable.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:riverpod/riverpod.dart';

import 'models.dart';

/// AI 配置状态
class AIConfigState extends Equatable {
  final AIConfig? config;
  final bool isLoading;
  final String? error;

  const AIConfigState({
    this.config,
    this.isLoading = false,
    this.error,
  });

  AIConfigState copyWith({
    AIConfig? config,
    bool? isLoading,
    String? error,
  }) {
    return AIConfigState(
      config: config ?? this.config,
      isLoading: isLoading ?? this.isLoading,
      error: error ?? this.error,
    );
  }

  @override
  List<Object?> get props => [config, isLoading, error];

  @override
  String toString() => 'AIConfigState(config: $config, isLoading: $isLoading)';
}

/// 终端容器状态
class TerminalState extends Equatable {
  final bool isRunning;
  final String? shellPrompt;
  final List<String> recentCommands;

  const TerminalState({
    this.isRunning = false,
    this.shellPrompt,
    this.recentCommands = const [],
  });

  TerminalState copyWith({
    bool? isRunning,
    String? shellPrompt,
    List<String>? recentCommands,
  }) {
    return TerminalState(
      isRunning: isRunning ?? this.isRunning,
      shellPrompt: shellPrompt ?? this.shellPrompt,
      recentCommands: recentCommands ?? this.recentCommands,
    );
  }

  @override
  List<Object?> get props => [isRunning, shellPrompt, recentCommands];

  @override
  String toString() => 'TerminalState(isRunning: $isRunning)';
}

/// MCP 服务器列表状态
class MCPServersState extends Equatable {
  final List<MCPServerInfo> servers;
  final bool isLoading;
  final String? error;

  const MCPServersState({
    this.servers = const [],
    this.isLoading = false,
    this.error,
  });

  MCPServersState copyWith({
    List<MCPServerInfo>? servers,
    bool? isLoading,
    String? error,
  }) {
    return MCPServersState(
      servers: servers ?? this.servers,
      isLoading: isLoading ?? this.isLoading,
      error: error ?? this.error,
    );
  }

  @override
  List<Object?> get props => [servers, isLoading, error];

  @override
  String toString() => 'MCPServersState(count: ${servers.length})';
}

/// AI 配置 Provider
final aiConfigProvider =
    AsyncNotifierProvider<AIConfigNotifier, AIConfigState>(AIConfigNotifier.new);

class AIConfigNotifier extends AsyncNotifier<AIConfigState> {
  @override
  Future<AIConfigState> build() async {
    // 延迟加载 SharedPreferences，避免在初始化时阻塞
    await Future.delayed(Duration.zero);

    final prefs = await SharedPreferences.getInstance();
    final baseUrl = prefs.getString('ai_base_url') ?? '';
    final apiKey = prefs.getString('ai_api_key') ?? '';
    final modelName = prefs.getString('ai_model_name') ?? 'gpt-3.5-turbo';
    final isStream = prefs.getBool('ai_is_stream') ?? true;

    return AIConfigState(
      config: AIConfig(
        baseUrl: baseUrl,
        apiKey: apiKey,
        modelName: modelName,
        isStream: isStream,
      ),
    );
  }

  Future<void> saveConfig(AIConfig config) async {
    state = const AsyncValue.loading();

    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('ai_base_url', config.baseUrl);
      await prefs.setString('ai_api_key', config.apiKey);
      await prefs.setString('ai_model_name', config.modelName);
      await prefs.setBool('ai_is_stream', config.isStream);

      state = AsyncValue.data(AIConfigState(config: config));
    } catch (e) {
      state = AsyncValue.error(
        AIConfigState(error: '保存配置失败: $e'),
        StackTrace.current,
      );
    }
  }

  Future<void> testConnection() async {
    final current = state.value;
    if (current == null || current.config == null) return;

    state = AsyncValue.loading();

    try {
      // 这里可以添加实际的连接测试逻辑
      // 暂时模拟成功
      await Future.delayed(const Duration(seconds: 1));
      state = AsyncValue.data(current);
    } catch (e) {
      state = AsyncValue.error(
        current.copyWith(error: '连接测试失败: $e'),
        StackTrace.current,
      );
    }
  }
}

/// 终端容器 Provider
final terminalStateProvider =
    AsyncNotifierProvider<TerminalNotifier, TerminalState>(TerminalNotifier.new);

class TerminalNotifier extends AsyncNotifier<TerminalState> {
  @override
  Future<TerminalState> build() async {
    return const TerminalState();
  }

  Future<void> startContainer() async {
    state = const AsyncValue.loading();

    try {
      // TODO: 实现实际的容器启动逻辑
      // 模拟启动过程
      await Future.delayed(const Duration(seconds: 2));
      state = AsyncValue.data(
        const TerminalState(isRunning: true, shellPrompt: '#'),
      );
    } catch (e) {
      state = AsyncValue.error(
        TerminalState(error: '启动容器失败: $e'),
        StackTrace.current,
      );
    }
  }

  Future<void> stopContainer() async {
    state = const AsyncValue.loading();

    try {
      // TODO: 实现实际的容器停止逻辑
      await Future.delayed(const Duration(seconds: 1));
      state = AsyncValue.data(const TerminalState(isRunning: false));
    } catch (e) {
      state = AsyncValue.error(
        TerminalState(error: '停止容器失败: $e'),
        StackTrace.current,
      );
    }
  }

  Future<void> executeCommand(String command) async {
    final current = state.value;
    if (current == null || !current.isRunning) return;

    try {
      // TODO: 实现实际的命令执行逻辑
      // 模拟执行
      final output = '$command\noutput: ...';
      final updatedCommands = [...current.recentCommands, command];

      state = AsyncValue.data(
        current.copyWith(
          recentCommands: updatedCommands,
          shellPrompt: '#',
        ),
      );
    } catch (e) {
      state = AsyncValue.error(
        TerminalState(error: '执行命令失败: $e'),
        StackTrace.current,
      );
    }
  }
}

/// MCP 服务器 Provider
final mcpServersProvider =
    AsyncNotifierProvider<MCPServersNotifier, MCPServersState>(MCPServersNotifier.new);

class MCPServersNotifier extends AsyncNotifier<MCPServersState> {
  @override
  Future<MCPServersState> build() async {
    // TODO: 加载 MCP 配置
    return const MCPServersState();
  }

  Future<void> loadServers() async {
    state = const AsyncValue.loading();

    try {
      // TODO: 从配置文件加载 MCP 服务器列表
      // 模拟加载
      await Future.delayed(const Duration(seconds: 1));
      state = AsyncValue.data(
        const MCPServersState(servers: [
          MCPServerInfo(name: 'file-tools', type: 'stdio', enabled: true),
          MCPServerInfo(name: 'web-search', type: 'http', enabled: false),
        ]),
      );
    } catch (e) {
      state = AsyncValue.error(
        MCPServersState(error: '加载 MCP 服务器失败: $e'),
        StackTrace.current,
      );
    }
  }

  Future<void> toggleServer(String name) async {
    final current = state.value;
    if (current == null) return;

    try {
      final updatedServers = current.servers.map((server) {
        if (server.name == name) {
          return server.copyWith(enabled: !server.enabled);
        }
        return server;
      }).toList();

      state = AsyncValue.data(
        current.copyWith(servers: updatedServers),
      );
    } catch (e) {
      state = AsyncValue.error(
        MCPServersState(error: '切换 MCP 服务器失败: $e'),
        StackTrace.current,
      );
    }
  }
}

/// MCP 服务器信息
class MCPServerInfo extends Equatable {
  final String name;
  final String type; // 'stdio' or 'http'
  final bool enabled;

  const MCPServerInfo({
    required this.name,
    required this.type,
    required this.enabled,
  });

  MCPServerInfo copyWith({
    String? name,
    String? type,
    bool? enabled,
  }) {
    return MCPServerInfo(
      name: name ?? this.name,
      type: type ?? this.type,
      enabled: enabled ?? this.enabled,
    );
  }

  @override
  List<Object> get props => [name, type, enabled];

  @override
  String toString() => 'MCPServerInfo(name: $name)';
}