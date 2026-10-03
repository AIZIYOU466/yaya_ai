import 'package:equatable/equatable.dart';

/// AI 配置模型
class AIConfig extends Equatable {
  final String baseUrl;
  final String apiKey;
  final String modelName;
  /// 端侧 .gguf 模型文件路径（为空表示端侧不可用，走云端）。
  final String modelPath;
  final bool isStream;

  const AIConfig({
    required this.baseUrl,
    required this.apiKey,
    required this.modelName,
    this.modelPath = '',
    this.isStream = true,
  });

  AIConfig copyWith({
    String? baseUrl,
    String? apiKey,
    String? modelName,
    String? modelPath,
    bool? isStream,
  }) {
    return AIConfig(
      baseUrl: baseUrl ?? this.baseUrl,
      apiKey: apiKey ?? this.apiKey,
      modelName: modelName ?? this.modelName,
      modelPath: modelPath ?? this.modelPath,
      isStream: isStream ?? this.isStream,
    );
  }

  @override
  List<Object> get props => [baseUrl, apiKey, modelName, modelPath, isStream];

  @override
  String toString() => 'AIConfig(baseUrl: $baseUrl, model: $modelName)';
}