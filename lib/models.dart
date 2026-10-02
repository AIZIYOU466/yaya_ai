import 'package:equatable/equatable.dart';

/// AI 配置模型
class AIConfig extends Equatable {
  final String baseUrl;
  final String apiKey;
  final String modelName;
  final bool isStream;

  const AIConfig({
    required this.baseUrl,
    required this.apiKey,
    required this.modelName,
    required this.isStream,
  });

  AIConfig copyWith({
    String? baseUrl,
    String? apiKey,
    String? modelName,
    bool? isStream,
  }) {
    return AIConfig(
      baseUrl: baseUrl ?? this.baseUrl,
      apiKey: apiKey ?? this.apiKey,
      modelName: modelName ?? this.modelName,
      isStream: isStream ?? this.isStream,
    );
  }

  @override
  List<Object> get props => [baseUrl, apiKey, modelName, isStream];

  @override
  String toString() => 'AIConfig(baseUrl: $baseUrl, model: $modelName)';
}

/// 聊天消息模型
class ChatMessage extends Equatable {
  final String role; // 'user', 'assistant', 'tool'
  final String content;
  final List<dynamic>? toolCalls;
  final List<String>? toolResults;

  const ChatMessage({
    required this.role,
    required this.content,
    this.toolCalls,
    this.toolResults,
  });

  ChatMessage copyWith({
    String? role,
    String? content,
    List<dynamic>? toolCalls,
    List<String>? toolResults,
  }) {
    return ChatMessage(
      role: role ?? this.role,
      content: content ?? this.content,
      toolCalls: toolCalls ?? this.toolCalls,
      toolResults: toolResults ?? this.toolResults,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'role': role,
      'content': content,
      if (toolCalls != null) 'tool_calls': toolCalls,
      if (toolResults != null) 'tool_results': toolResults,
    };
  }

  @override
  List<Object> get props => [role, content, toolCalls, toolResults];

  @override
  String toString() => 'ChatMessage(role: $role)';
}

/// 工具执行结果
class ToolResult extends Equatable {
  final String type; // 'text', 'image', 'file', etc.
  final dynamic data;

  const ToolResult({
    required this.type,
    required this.data,
  });

  @override
  List<Object?> get props => [type, data];

  @override
  String toString() => 'ToolResult(type: $type)';
}