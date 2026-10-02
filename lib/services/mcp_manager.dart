import 'dart:convert';
import 'dart:io';
import 'package:dio/dio.dart';

/// MCP 服务器配置
class MCPServerConfig {
  final String name;
  final String type; // 'stdio' or 'http'
  final String? command;
  final List<String>? args;
  final String? url;
  final Map<String, String>? headers;

  MCPServerConfig({
    required this.name,
    required this.type,
    this.command,
    this.args,
    this.url,
    this.headers,
  });
}

/// MCP 工具定义
class MCPTool {
  final String name;
  final String description;
  final Map<String, dynamic> schema;

  MCPTool({
    required this.name,
    required this.description,
    required this.schema,
  });
}

/// MCP 服务器管理器
class MCPManager {
  final Map<String, Process> _stdioServers = {};
  final Dio _dio = Dio();
  final Map<String, List<MCPTool>> _tools = {};

  /// 加载 MCP 配置
  Future<List<MCPServerConfig>> loadConfig() async {
    final configFile = File('~/.aicode/mcp.json');
    if (!configFile.existsSync()) {
      return [];
    }

    final content = await configFile.readAsString();
    final data = jsonDecode(content) as Map<String, dynamic>;
    final servers = data['servers'] as List<dynamic>?;

    if (servers == null) {
      return [];
    }

    return servers.map((server) {
      final serverData = server as Map<String, dynamic>;
      return MCPServerConfig(
        name: serverData['name'] as String? ?? '',
        type: serverData['type'] as String? ?? 'stdio',
        command: serverData['command'] as String?,
        args: (serverData['args'] as List<dynamic>?)?.cast<String>(),
        url: serverData['url'] as String?,
        headers: (serverData['headers'] as Map<String, dynamic>?)?.cast<String, String>(),
      );
    }).toList();
  }

  /// 保存 MCP 配置
  Future<void> saveConfig(List<MCPServerConfig> configs) async {
    final configFile = File('~/.aicode/mcp.json');
    await configFile.create(recursive: true);

    final data = {
      'servers': configs.map((config) {
        return {
          'name': config.name,
          'type': config.type,
          if (config.command != null) 'command': config.command,
          if (config.args != null) 'args': config.args,
          if (config.url != null) 'url': config.url,
          if (config.headers != null) 'headers': config.headers,
        };
      }).toList(),
    };

    await configFile.writeAsString(jsonEncode(data));
  }

  /// 启动 stdio 服务器
  Future<bool> startStdioServer(MCPServerConfig config) async {
    try {
      if (config.command == null) {
        return false;
      }

      final process = await Process.start(
        config.command!,
        config.args ?? [],
        mode: ProcessStartMode.normal,
      );

      _stdioServers[config.name] = process;

      // 读取初始输出以发现工具
      await Future.delayed(const Duration(seconds: 2));
      await _discoverTools(config.name);

      return true;
    } catch (e) {
      return false;
    }
  }

  /// 停止 stdio 服务器
  Future<void> stopStdioServer(String name) async {
    final process = _stdioServers[name];
    if (process != null) {
      process.kill(ProcessSignal.sigterm);
      await process.exitCode;
      _stdioServers.remove(name);
      _tools.remove(name);
    }
  }

  /// 发现工具
  Future<void> _discoverTools(String serverName) async {
    final process = _stdioServers[serverName];
    if (process == null) {
      return;
    }

    // 发送 tools/list 请求
    process.stdin.writeln(jsonEncode({
      'jsonrpc': '2.0',
      'id': 1,
      'method': 'tools/list',
      'params': {},
    }));

    // 读取响应
    final response = await process.stdout.first;
    final data = jsonDecode(utf8.decode(response)) as Map<String, dynamic>;

    if (data.containsKey('result')) {
      final tools = data['result']['tools'] as List<dynamic>?;
      if (tools != null) {
        _tools[serverName] = tools.map((tool) {
          final toolData = tool as Map<String, dynamic>;
          return MCPTool(
            name: toolData['name'] as String? ?? '',
            description: toolData['description'] as String? ?? '',
            schema: toolData['schema'] as Map<String, dynamic>? ?? {},
          );
        }).toList();
      }
    }
  }

  /// 调用工具
  Future<dynamic> callTool(
    String serverName,
    String toolName,
    Map<String, dynamic> params,
  ) async {
    final process = _stdioServers[serverName];
    if (process == null) {
      throw Exception('Server $serverName not running');
    }

    final tool = _tools[serverName]?.firstWhere(
      (t) => t.name == toolName,
      orElse: () => throw Exception('Tool $toolName not found'),
    );

    if (tool == null) {
      throw Exception('Tool $toolName not found');
    }

    process.stdin.writeln(jsonEncode({
      'jsonrpc': '2.0',
      'id': DateTime.now().millisecondsSinceEpoch,
      'method': 'tools/call',
      'params': {
        'name': toolName,
        'arguments': params,
      },
    }));

    final response = await process.stdout.first;
    final data = jsonDecode(utf8.decode(response)) as Map<String, dynamic>;

    return data['result'];
  }

  /// 获取服务器工具列表
  List<MCPTool> getTools(String serverName) {
    return _tools[serverName] ?? [];
  }

  /// 获取所有工具
  Map<String, List<MCPTool>> getAllTools() {
    return Map.from(_tools);
  }

  /// 释放资源
  Future<void> dispose() async {
    for (final process in _stdioServers.values) {
      process.kill(ProcessSignal.sigterm);
    }
    _stdioServers.clear();
    _tools.clear();
  }
}
