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
  final _modelPathController = TextEditingController();
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
    _modelPathController.dispose();
    super.dispose();
  }

  Future<void> _loadConfig() async {
    final config = await ref.read(aiConfigProvider.future);
    if (config.config != null) {
      _baseUrlController.text = config.config!.baseUrl;
      _apiKeyController.text = config.config!.apiKey;
      _modelNameController.text = config.config!.modelName;
      _modelPathController.text = config.config!.modelPath;
      setState(() {
        _isStream = config.config!.isStream;
      });
    }
  }

  Future<void> _saveConfig() async {
    setState(() {
      _isLoading = true;
    });

    final baseUrl = _baseUrlController.text.trim();
    final modelName = _modelNameController.text.trim();
    if (baseUrl.isEmpty || modelName.isEmpty) {
      setState(() => _isLoading = false);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Base URL 与模型名称不能为空')),
      );
      return;
    }

    try {
      final config = AIConfig(
        baseUrl: baseUrl,
        apiKey: _apiKeyController.text.trim(),
        modelName: modelName,
        modelPath: _modelPathController.text.trim(),
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
      // 检查真实结果（testConnection 失败走 AsyncValue.error，await 不抛异常）。
      final after = ref.read(aiConfigProvider);
      final err = after.hasError ? after.error : after.valueOrNull?.error;
      if (err != null) {
        throw Exception(err);
      }
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

  static const _providers = <String, ({String baseUrl, String model})>{
    'OpenAI': (baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini'),
    'DeepSeek': (baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat'),
    'OpenRouter': (
      baseUrl: 'https://openrouter.ai/api/v1',
      model: 'openai/gpt-4o-mini'
    ),
    'Moonshot': (baseUrl: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k'),
    '硅基流动': (
      baseUrl: 'https://api.siliconflow.cn/v1',
      model: 'Qwen/Qwen2.5-7B-Instruct'
    ),
    '智谱': (baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash'),
  };

  void _applyPreset(String name) {
    final p = _providers[name];
    if (p == null) return;
    setState(() {
      _baseUrlController.text = p.baseUrl;
      _modelNameController.text = p.model;
    });
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('已填入 $name 预设，请补充 API Key')),
    );
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
                  const SizedBox(height: 12),
                  Text(
                    '预设供应商（点击填入）',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 8,
                    runSpacing: 4,
                    children: [
                      for (final e in _providers.entries)
                        ActionChip(
                          label: Text(e.key),
                          onPressed: () => _applyPreset(e.key),
                        ),
                    ],
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
                  MaskedInput(
                    controller: _modelPathController,
                    hintText: '端侧 .gguf 路径（可选，如 /sdcard/Download/model.gguf）',
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
