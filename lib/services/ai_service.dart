import 'dart:convert';
import 'package:dio/dio.dart';

import 'models.dart';

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

      final stream = response.data as Stream<List<int>>;
      await for (final chunk in stream) {
        final data = utf8.decode(chunk);
        yield data;
      }
    } on DioException catch (e) {
      throw AIError('HTTP ${e.response?.statusCode}: ${e.message}', code: e.response?.statusCode);
    } catch (e) {
      throw AIError('Request failed: $e');
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
