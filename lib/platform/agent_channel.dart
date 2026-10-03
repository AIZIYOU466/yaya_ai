import 'dart:convert';

import 'package:flutter/services.dart';

/// Dart UI ↔ Kotlin 执行层通道（AGENTS.md R5）。
///
/// Agent 循环在 Rust Core 中运行，本通道只负责：启动/停止任务、接收事件流、
/// 以及终端容器的启停与命令流。
class AgentChannel {
  static const _method = MethodChannel('com.yaya.ai/agent');
  static const _events = EventChannel('com.yaya.ai/agent/events');
  static const _model = EventChannel('com.yaya.ai/model');
  static const _timeout = Duration(seconds: 10);
  static const _startTimeout = Duration(seconds: 30);

  /// Agent 事件流（每项为事件 JSON 字符串，协议见 core/src/agent/events.rs）。
  static Stream<String> get agentEvents =>
      _events.receiveBroadcastStream().map((event) => event as String);

  /// 启动一次 Agent 任务（在 Kotlin 后台线程运行，事件经 [agentEvents] 回推）。
  static Future<bool> startAgent({
    required String taskId,
    required String prompt,
    required Map<String, dynamic> config,
  }) async {
    final ok = await _method.invokeMethod<bool>('startAgent', {
      'taskId': taskId,
      'prompt': prompt,
      'configJson': jsonEncode(config),
    }).timeout(_timeout);
    return ok ?? false;
  }

  static Future<bool> stopAgent() async =>
      await _method.invokeMethod<bool>('stopAgent').timeout(_timeout) ?? false;

  /// 回传用户对一次授权请求的选择（见事件 `approval_request`）。
  static Future<bool> respondApproval({required String id, required bool allow}) async =>
      await _method
              .invokeMethod<bool>('respondApproval', {'id': id, 'allow': allow})
              .timeout(_timeout) ??
          false;

  /// 最近会话（`{sessionId,title,messages:[...]}`）；无会话返回 null。
  static Future<String?> loadRecentSession() async =>
      await _method.invokeMethod<String>('loadRecentSession').timeout(_timeout);

  /// 会话检查点列表（`[{messages:[...]}]`，新 → 旧）。
  static Future<String> checkpoints(String sessionId) async =>
      await _method
              .invokeMethod<String>('checkpoints', {'sessionId': sessionId})
              .timeout(_timeout) ??
          '[]';

  /// 全局统计（`{sessions,messages,toolCalls,errors,totalTokens,checkpoints}`）。
  static Future<String> getStats() async =>
      await _method.invokeMethod<String>('getStats').timeout(_timeout) ?? '{}';

  /// 工作区根路径（App 私有目录 `workspace/`）。
  static Future<String> workspaceRoot() async =>
      await _method.invokeMethod<String>('workspaceRoot').timeout(_timeout) ?? '';

  /// 工作区目录列表（`{ok,content}`，条目名每行一个，目录带 `/` 后缀）。
  static Future<String> workspaceList(String path) async =>
      await _method
              .invokeMethod<String>('workspaceList', {'path': path})
              .timeout(_timeout) ??
          '{"ok":false}';

  /// 端侧本地模型是否可用（非 STUB）。
  static Future<bool> localAvailable() async =>
      await _method.invokeMethod<bool>('localAvailable').timeout(_timeout) ?? false;

  /// 当前网络是否可达（供路由 hints 的 networkOk 使用）。
  static Future<bool> networkAvailable() async =>
      await _method.invokeMethod<bool>('networkAvailable').timeout(_timeout) ?? false;

  /// Linux rootfs 是否已安装（终端容器可用）。
  static Future<bool> rootfsInstalled() async =>
      await _method.invokeMethod<bool>('rootfsInstalled').timeout(_timeout) ?? false;

  /// 镜像目录列表（含内置与自定义）。
  static Future<List<Map<String, dynamic>>> rootfsProfiles() async {
    final raw = await _method
            .invokeMethod<String>('rootfsProfiles')
            .timeout(_timeout) ??
        '[]';
    return (jsonDecode(raw) as List)
        .map((e) => e as Map<String, dynamic>)
        .toList();
  }

  /// 当前使用中的镜像 id。
  static Future<String> currentRootfs() async =>
      await _method.invokeMethod<String>('currentRootfs').timeout(_timeout) ?? 'alpine';

  /// 下载并安装指定镜像（阻塞至完成，官方源 + SHA256 校验）。
  static Future<String> installRootfs(String id) async =>
      await _method.invokeMethod<String>('installRootfs', {'id': id})
              .timeout(const Duration(minutes: 15)) ??
          '未知结果';

  /// 切换当前使用镜像。
  static Future<bool> setCurrentRootfs(String id) async =>
      await _method.invokeMethod<bool>('setCurrentRootfs', {'id': id}).timeout(_timeout) ?? false;

  /// 重置（删除）指定镜像。
  static Future<String> resetRootfs(String id) async =>
      await _method.invokeMethod<String>('resetRootfs', {'id': id}).timeout(_timeout) ?? '未知结果';

  /// 添加自定义镜像（URL 指向 tar.gz rootfs），返回新镜像 id。
  static Future<String> addRootfsProfile({
    required String name,
    required String url,
    String sha256 = '',
  }) async =>
      await _method.invokeMethod<String>('addRootfsProfile', {
        'name': name,
        'url': url,
        'sha256': sha256,
      }).timeout(_timeout) ?? '添加失败';

  /// 删除自定义镜像。
  static Future<String> removeRootfsProfile(String id) async =>
      await _method.invokeMethod<String>('removeRootfsProfile', {'id': id}).timeout(_timeout) ?? '删除失败';

  static Future<bool> startContainer() async =>
      await _method.invokeMethod<bool>('startContainer').timeout(_startTimeout) ?? false;

  static Future<bool> stopContainer() async =>
      await _method.invokeMethod<bool>('stopContainer').timeout(_timeout) ?? false;

  /// 最近一次容器启动失败的详细原因。
  static Future<String?> containerError() async =>
      await _method.invokeMethod<String>('containerError').timeout(_timeout);

  /// 在终端容器执行命令，逐行返回输出。
  static Stream<String> executeCommand(String command) async* {
    final stream = _model.receiveBroadcastStream(jsonEncode({'command': command}));
    await for (final chunk in stream) {
      yield chunk as String;
    }
  }
}