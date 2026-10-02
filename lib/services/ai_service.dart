import 'dart:convert';
import 'package:dio/dio.dart';

import '../models.dart';

/// AI 服务错误
class AIError implements Exception {
  final String message;
  final int? code;

  AIError(this.message, {this.code});

  @override
  String toString() => 'AIError($code): $message';
}

/// AI 服务类
class AIService {
  final Dio _dio;

  AIService({Dio? dio}) : _dio = dio ?? Dio();

  /// 发送聊天请求（流式）
  Stream<String> streamChat(
    List<ChatMessage> messages, {
    String? model,
    String? baseUrl,
    String? apiKey,
    Map<String, dynamic>? options,
  }) async* {
    if (baseUrl == null || baseUrl.isEmpty) {
      throw AIError('Base URL is required');
    }

    final uri = Uri.parse(baseUrl);
    final url = '${uri.origin}${uri.path.replaceAll(RegExp(r'/+$'), '')}/chat/completions';

    final headers = <String, String>{
      'Content-Type': 'application/json',
      'Authorization': 'Bearer $apiKey',
      ...?options?['headers'],
    };

    final body = {
      'model': model ?? 'gpt-3.5-turbo',
      'messages': messages.map((m) => m.toMap()).toList(),
      'stream': true,
      ...?options?['params'],
    };

    try {
      final response = await _dio.post(
        url,
        data: body,
        options: Options(
          headers: headers,
          responseType: ResponseType.stream,
        ),
      );

      // dio 5.x：stream 响应类型下 data 是 ResponseBody（不是 Stream<List<int>>）
      final responseBody = response.data as ResponseBody;
      var pending = '';
      await for (final chunk in responseBody.stream) {
        pending += utf8.decode(chunk, allowMalformed: true);
        final lines = pending.split('\n');
        pending = lines.removeLast(); // 保留可能跨 chunk 截断的半行
        for (final line in lines) {
          final token = _parseSseLine(line);
          if (token != null) yield token;
        }
      }
      final last = _parseSseLine(pending);
      if (last != null) yield last;
    } on DioException catch (e) {
      throw AIError('HTTP ${e.response?.statusCode}: ${e.message}', code: e.response?.statusCode);
    } catch (e) {
      throw AIError('Request failed: $e');
    }
  }

  /// 解析一行 SSE 数据，返回 delta.content 文本；非 data 行/无法解析返回 null
  String? _parseSseLine(String line) {
    final trimmed = line.trim();
    if (!trimmed.startsWith('data:')) return null;
    final payload = trimmed.substring(5).trim();
    if (payload.isEmpty || payload == '[DONE]') return null;
    try {
      final json = jsonDecode(payload) as Map<String, dynamic>;
      final choices = json['choices'] as List<dynamic>?;
      if (choices == null || choices.isEmpty) return null;
      final delta = choices[0]['delta'] as Map<String, dynamic>?;
      return delta?['content'] as String?;
    } catch (_) {
      return null; // keep-alive 等无法解析的行
    }
  }

  /// 发送聊天请求（非流式）
  Future<ChatMessage> sendChat(
    List<ChatMessage> messages, {
    String? model,
    String? baseUrl,
    String? apiKey,
    Map<String, dynamic>? options,
  }) async {
    final uri = Uri.parse(baseUrl ?? '');
    final url = '${uri.origin}${uri.path.replaceAll(RegExp(r'/+$'), '')}/chat/completions';

    final headers = <String, String>{
      'Content-Type': 'application/json',
      'Authorization': 'Bearer $apiKey',
      ...?options?['headers'],
    };

    final body = {
      'model': model ?? 'gpt-3.5-turbo',
      'messages': messages.map((m) => m.toMap()).toList(),
      'stream': false,
      ...?options?['params'],
    };

    try {
      final response = await _dio.post(
        url,
        data: body,
        options: Options(headers: headers),
      );

      final data = response.data as Map<String, dynamic>;
      final choices = data['choices'] as List<dynamic>?;
      if (choices == null || choices.isEmpty) {
        throw AIError('No response from AI');
      }

      final message = choices[0]['message'] as Map<String, dynamic>?;
      if (message == null) {
        throw AIError('Invalid response format');
      }

      final content = message['content'] as String?;
      final toolCalls = message['tool_calls'] as List<dynamic>?;

      return ChatMessage(
        role: message['role'] as String? ?? 'assistant',
        content: content ?? '',
        toolCalls: toolCalls,
      );
    } on DioException catch (e) {
      throw AIError('HTTP ${e.response?.statusCode}: ${e.message}', code: e.response?.statusCode);
    } catch (e) {
      throw AIError('Request failed: $e');
    }
  }

  /// 发送工具调用请求
  Future<Map<String, dynamic>> sendToolCall(
    String toolName,
    Map<String, dynamic> parameters, {
    String? model,
    String? baseUrl,
    String? apiKey,
  }) async {
    final uri = Uri.parse(baseUrl ?? '');
    final url = '${uri.origin}${uri.path.replaceAll(RegExp(r'/+$'), '')}/tools/$toolName';

    final headers = <String, String>{
      'Content-Type': 'application/json',
      'Authorization': 'Bearer $apiKey',
    };

    try {
      final response = await _dio.post(
        url,
        data: parameters,
        options: Options(headers: headers),
      );

      return response.data as Map<String, dynamic>;
    } on DioException catch (e) {
      throw AIError('HTTP ${e.response?.statusCode}: ${e.message}', code: e.response?.statusCode);
    } catch (e) {
      throw AIError('Tool call failed: $e');
    }
  }
}
