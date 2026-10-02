import 'dart:convert';
import 'package:dio/dio.dart';

class McpHttpClient {
  final Dio _dio = Dio();
  final String baseUrl;

  McpHttpClient(this.baseUrl);

  Future<List<Map<String, dynamic>>> listTools() async {
    final response = await _dio.post('$baseUrl/tools/list');
    final result = response.data['result'] as Map<String, dynamic>?;
    if (result == null) return [];
    final tools = result['tools'] as List<dynamic>? ?? [];
    return tools.cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> callTool(
    String toolName,
    Map<String, dynamic> arguments,
  ) async {
    final response = await _dio.post('$baseUrl/tools/call', data: {
      'name': toolName,
      'arguments': arguments,
    });
    return response.data['result'] as Map<String, dynamic>;
  }
}
