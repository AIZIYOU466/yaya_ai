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
  List<Object?> get props => [role, content, toolCalls, toolResults];

  @override
  String toString() => 'ChatMessage(role: $role)';
}

/// 屏幕节点
class ScreenNode extends Equatable {
  final String id;
  final String text;
  final String className;
  final String bounds;
  final List<ScreenNode> children;

  const ScreenNode({
    required this.id,
    this.text = '',
    this.className = '',
    this.bounds = '',
    this.children = const [],
  });

  factory ScreenNode.fromJson(Map<String, dynamic> json) {
    final children = (json['children'] as List<dynamic>?)
        ?.map((c) => ScreenNode.fromJson(c as Map<String, dynamic>))
        .toList() ??
        [];
    return ScreenNode(
      id: json['id'] as String? ?? '',
      text: json['text'] as String? ?? '',
      className: json['className'] as String? ?? '',
      bounds: json['bounds'] as String? ?? '',
      children: children,
    );
  }

  @override
  List<Object?> get props => [id, text, className, bounds, children];
}

/// 操作请求
class ActionRequest extends Equatable {
  final String type; // 'click', 'input', 'scroll'
  final String? nodeId;
  final String? text;

  const ActionRequest({
    required this.type,
    this.nodeId,
    this.text,
  });

  Map<String, dynamic> toMap() {
    return {
      'type': type,
      if (nodeId != null) 'nodeId': nodeId,
      if (text != null) 'text': text,
    };
  }

  @override
  List<Object?> get props => [type, nodeId, text];
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