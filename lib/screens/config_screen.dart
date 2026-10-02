import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models.dart';
import '../providers.dart';
import '../widgets/glass_card.dart';

class ConfigScreen extends ConsumerStatefulWidget {
  const ConfigScreen({super.key});

  @override
  ConsumerState<ConfigScreen> createState() => _ConfigScreenState();
}

class _ConfigScreenState extends ConsumerState<ConfigScreen> {
  final _baseUrlController = TextEditingController();
  final _apiKeyController = TextEditingController();
  final _modelNameController = TextEditingController();
  bool _isStream = true;
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    // 加载配置
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _loadConfig();
    });
  }

  @override
  void dispose() {
    _baseUrlController.dispose();
    _apiKeyController.dispose();
    _modelNameController.dispose();
    super.dispose();
  }

  Future<void> _loadConfig() async {
    final config = await ref.read(aiConfigProvider.future);
    if (config.config != null) {
      _baseUrlController.text = config.config!.baseUrl;
      _apiKeyController.text = config.config!.apiKey;
      _modelNameController.text = config.config!.modelName;
      setState(() {
        _isStream = config.config!.isStream;
      });
    }
  }

  Future<void> _saveConfig() async {
    setState(() {
      _isLoading = true;
    });

    try {
      final config = AIConfig(
        baseUrl: _baseUrlController.text.trim(),
        apiKey: _apiKeyController.text.trim(),
        modelName: _modelNameController.text.trim(),
        isStream: _isStream,
      );

      await ref.read(aiConfigProvider.notifier).saveConfig(config);

      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('配置已保存')),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('保存失败: $e')),
      );
    } finally {
      setState(() {
        _isLoading = false;
      });
    }
  }

  Future<void> _testConnection() async {
    setState(() {
      _isLoading = true;
    });

    try {
      await ref.read(aiConfigProvider.notifier).testConnection();

      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('连接成功')),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('连接失败: $e')),
      );
    } finally {
      setState(() {
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('AI 配置'),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            GlassCard(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'AI 提供商配置',
                    style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 16),
                  MaskedInput(
                    controller: _baseUrlController,
                    hintText: 'Base URL (e.g., https://api.openai.com/v1)',
                  ),
                  const SizedBox(height: 16),
                  MaskedInput(
                    controller: _apiKeyController,
                    hintText: 'API Key',
                    obscureText: true,
                  ),
                  const SizedBox(height: 16),
                  MaskedInput(
                    controller: _modelNameController,
                    hintText: 'Model Name (e.g., gpt-3.5-turbo)',
                  ),
                  const SizedBox(height: 16),
                  Row(
                    children: [
                      const Text('流式响应:'),
                      Switch(
                        value: _isStream,
                        onChanged: (value) {
                          setState(() {
                            _isStream = value;
                          });
                        },
                      ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 24),
            Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: _isLoading ? null : _saveConfig,
                    icon: const Icon(Icons.save),
                    label: const Text('保存配置'),
                    style: ElevatedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 16),
                    ),
                  ),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: _isLoading ? null : _testConnection,
                    icon: const Icon(Icons.wifi),
                    label: const Text('测试连接'),
                    style: ElevatedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 16),
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
