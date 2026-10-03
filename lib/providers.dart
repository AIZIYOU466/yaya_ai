import 'dart:convert';

import 'package:equatable/equatable.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:riverpod/riverpod.dart';

import 'models.dart';
import 'platform/agent_channel.dart';

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
    final modelPath = prefs.getString('ai_model_path') ?? '';
    final isStream = prefs.getBool('ai_is_stream') ?? true;

    return AIConfigState(
      config: AIConfig(
        baseUrl: baseUrl,
        apiKey: apiKey,
        modelName: modelName,
        modelPath: modelPath,
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
      await prefs.setString('ai_model_path', config.modelPath);
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
      final cfg = current.config!;
      // 真实检查（不再模拟成功）：端侧库是否就绪 + 云端地址是否合法。
      final localOk = await AgentChannel.localAvailable();
      final uri = Uri.tryParse(cfg.baseUrl);
      final cloudOk = cfg.baseUrl.isNotEmpty &&
          uri != null &&
          (uri.scheme == 'http' || uri.scheme == 'https');
      if (!localOk && !cloudOk) {
        state = AsyncValue.error(
          AIConfigState(
            config: cfg,
            error: '端侧模型未就绪，且云端 Base URL 不合法（需 http(s):// 开头）',
          ),
          StackTrace.current,
        );
        return;
      }
      state = AsyncValue.data(AIConfigState(config: cfg));
    } catch (e) {
      state = AsyncValue.error(
        AIConfigState(config: current.config, error: '连接测试失败: $e'),
        StackTrace.current,
      );
    }
  }
}

/// MCP 服务器 Provider
final mcpServersProvider =
    AsyncNotifierProvider<MCPServersNotifier, MCPServersState>(MCPServersNotifier.new);

class MCPServersNotifier extends AsyncNotifier<MCPServersState> {
  static const _prefsKey = 'mcp_servers';

  @override
  Future<MCPServersState> build() async {
    await Future.delayed(Duration.zero);
    return _load();
  }

  Future<MCPServersState> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_prefsKey);
    if (raw == null || raw.isEmpty) return const MCPServersState();
    try {
      final list = (jsonDecode(raw) as List)
          .map((e) => MCPServerInfo.fromJson(e as Map<String, dynamic>))
          .toList();
      return MCPServersState(servers: list);
    } catch (e) {
      throw Exception('MCP 配置解析失败: $e');
    }
  }

  Future<void> loadServers() async {
    state = const AsyncValue.loading();
    state = await AsyncValue.guard(_load);
  }

  Future<void> toggleServer(String name) async {
    final current = state.value;
    if (current == null) return;

    final updatedServers = current.servers
        .map((server) => server.name == name
            ? server.copyWith(enabled: !server.enabled)
            : server)
        .toList();

    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(
        _prefsKey,
        jsonEncode(updatedServers.map((s) => s.toJson()).toList()),
      );
      state = AsyncValue.data(current.copyWith(servers: updatedServers));
    } catch (e) {
      state = AsyncValue.error(
        MCPServersState(error: '保存 MCP 配置失败: $e'),
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
  final String? url;
  final String? command;
  final List<String> args;

  const MCPServerInfo({
    required this.name,
    required this.type,
    required this.enabled,
    this.url,
    this.command,
    this.args = const [],
  });

  factory MCPServerInfo.fromJson(Map<String, dynamic> json) {
    return MCPServerInfo(
      name: json['name'] as String? ?? '',
      type: json['type'] as String? ?? 'stdio',
      enabled: json['enabled'] as bool? ?? false,
      url: json['url'] as String?,
      command: json['command'] as String?,
      args: (json['args'] as List?)?.map((e) => e as String).toList() ?? const [],
    );
  }

  /// 与下发给 Kotlin 的 `mcpServers` 字段保持一致。
  Map<String, dynamic> toJson() => {
        'name': name,
        'type': type,
        'enabled': enabled,
        if (url != null) 'url': url,
        if (command != null) 'command': command,
        'args': args,
      };

  MCPServerInfo copyWith({
    String? name,
    String? type,
    bool? enabled,
    String? url,
    String? command,
    List<String>? args,
  }) {
    return MCPServerInfo(
      name: name ?? this.name,
      type: type ?? this.type,
      enabled: enabled ?? this.enabled,
      url: url ?? this.url,
      command: command ?? this.command,
      args: args ?? this.args,
    );
  }

  @override
  List<Object?> get props => [name, type, enabled, url, command, args];

  @override
  String toString() => 'MCPServerInfo(name: $name)';
}