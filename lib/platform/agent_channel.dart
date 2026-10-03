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

  /// 端侧本地模型是否可用（非 STUB）。
  static Future<bool> localAvailable() async =>
      await _method.invokeMethod<bool>('localAvailable').timeout(_timeout) ?? false;

  /// 当前网络是否可达（供路由 hints 的 networkOk 使用）。
  static Future<bool> networkAvailable() async =>
      await _method.invokeMethod<bool>('networkAvailable').timeout(_timeout) ?? false;

  /// Linux rootfs 是否已安装（终端容器可用）。
  static Future<bool> rootfsInstalled() async =>
      await _method.invokeMethod<bool>('rootfsInstalled').timeout(_timeout) ?? false;

  /// 下载并安装 Alpine rootfs（阻塞至完成，约 4MB，官方源 + SHA256 校验）。
  static Future<String> installRootfs() async =>
      await _method.invokeMethod<String>('installRootfs')
              .timeout(const Duration(minutes: 15)) ??
          '未知结果';

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