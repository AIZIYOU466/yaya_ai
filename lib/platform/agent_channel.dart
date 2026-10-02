import 'dart:convert';
import 'package:flutter/services.dart';

class AgentChannel {
  static const _method = MethodChannel('com.yaya.ai/agent');
  static const _model = EventChannel('com.yaya.ai/model');

  static Future<Map<String, dynamic>> observeScreen() async {
    final result = await _method.invokeMethod<String>('observeScreen');
    if (result == null) return {};
    return jsonDecode(result) as Map<String, dynamic>;
  }

  static Future<bool> executeAction(Map<String, dynamic> action) async {
    final data = jsonEncode(action);
    return await _method.invokeMethod<bool>('executeAction', data) ?? false;
  }

  static Future<bool> startAgentService() async {
    return await _method.invokeMethod<bool>('startAgentService') ?? false;
  }

  static Future<bool> stopAgentService() async {
    return await _method.invokeMethod<bool>('stopAgentService') ?? false;
  }

  static Future<bool> startContainer() async {
    return await _method.invokeMethod<bool>('startContainer') ?? false;
  }

  static Future<bool> stopContainer() async {
    return await _method.invokeMethod<bool>('stopContainer') ?? false;
  }

  static Stream<String> executeCommand(String command) async* {
    final stream = _model.receiveBroadcastStream(command);
    await for (final chunk in stream) {
      yield chunk as String;
    }
  }

  static Stream<String> runModel(String prompt, {String? modelPath}) async* {
    final params = jsonEncode({'prompt': prompt, if (modelPath != null) 'modelPath': modelPath});
    final stream = _model.receiveBroadcastStream(params);
    await for (final token in stream) {
      yield token as String;
    }
  }
}
