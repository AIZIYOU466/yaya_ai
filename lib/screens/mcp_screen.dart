import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers.dart';
import '../widgets/glass_card.dart';

class MCPScreen extends ConsumerStatefulWidget {
  const MCPScreen({super.key});

  @override
  ConsumerState<MCPScreen> createState() => _MCPScreenState();
}

class _MCPScreenState extends ConsumerState<MCPScreen> {
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _loadServers();
    });
  }

  Future<void> _loadServers() async {
    setState(() {
      _isLoading = true;
    });

    try {
      await ref.read(mcpServersProvider.notifier).loadServers();
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('加载失败: $e')),
      );
    } finally {
      setState(() {
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final mcpState = ref.watch(mcpServersProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('MCP 服务器'),
        actions: [
          IconButton(
            icon: const Icon(Icons.add),
            onPressed: () {
              // TODO: 添加 MCP 服务器
            },
          ),
        ],
      ),
      body: mcpState.when(
        data: (data) {
          return ListView.builder(
            padding: const EdgeInsets.all(16),
            itemCount: data.servers.length,
            itemBuilder: (context, index) {
              final server = data.servers[index];
              return Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: GlassCard(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Text(
                              server.name,
                              style: const TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            Switch(
                              value: server.enabled,
                              onChanged: (value) {
                                ref
                                    .read(mcpServersProvider.notifier)
                                    .toggleServer(server.name);
                              },
                            ),
                          ],
                        ),
                        const SizedBox(height: 8),
                        Text('类型: ${server.type}'),
                        if (server.url != null)
                          Text('URL: ${server.url}'),
                        if (server.command != null)
                          Text('命令: ${server.command}'),
                      ],
                    ),
                  ),
                ),
              );
            },
          );
        },
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, stack) => Center(child: Text('错误: $error')),
      ),
    );
  }
}
