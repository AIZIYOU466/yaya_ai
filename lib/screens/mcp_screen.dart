import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models.dart';
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
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('加载失败: $e')),
      );
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  Future<void> _addServer() async {
    final server = await _showServerDialog();
    if (server == null || server.name.isEmpty) return;
    await ref.read(mcpServersProvider.notifier).addServer(server);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('已添加服务器 ${server.name}')),
    );
  }

  Future<void> _editServer(MCPServerInfo existing) async {
    final server = await _showServerDialog(existing: existing);
    if (server == null || server.name.isEmpty) return;
    await ref.read(mcpServersProvider.notifier).updateServer(server);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('已保存 ${server.name}')),
    );
  }

  Future<void> _removeServer(MCPServerInfo server) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除服务器'),
        content: Text('确定删除 "${server.name}" 吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    await ref.read(mcpServersProvider.notifier).removeServer(server.name);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('已删除 ${server.name}')),
    );
  }

  Future<MCPServerInfo?> _showServerDialog({MCPServerInfo? existing}) async {
    final nameCtrl = TextEditingController(text: existing?.name ?? '');
    final commandCtrl = TextEditingController(text: existing?.command ?? '');
    final argsCtrl =
        TextEditingController(text: (existing?.args ?? []).join(' '));
    final urlCtrl = TextEditingController(text: existing?.url ?? '');
    var type = existing?.type ?? 'stdio';
    var enabled = existing?.enabled ?? true;

    final result = await showDialog<MCPServerInfo>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: Text(existing == null ? '新增 MCP 服务器' : '编辑服务器'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: nameCtrl,
                  decoration:
                      const InputDecoration(labelText: '名称（不得包含 __）'),
                ),
                DropdownButtonFormField<String>(
                  value: type,
                  items: const [
                    DropdownMenuItem(value: 'stdio', child: Text('stdio（本机命令）')),
                    DropdownMenuItem(value: 'http', child: Text('http（暂不支持）')),
                  ],
                  onChanged: (v) => setDialogState(() => type = v ?? 'stdio'),
                  decoration: const InputDecoration(labelText: '类型'),
                ),
                if (type == 'stdio') ...[
                  TextField(
                    controller: commandCtrl,
                    decoration:
                        const InputDecoration(labelText: '启动命令（如 npx）'),
                  ),
                  TextField(
                    controller: argsCtrl,
                    decoration: const InputDecoration(labelText: '启动参数（空格分隔）'),
                  ),
                ] else
                  TextField(
                    controller: urlCtrl,
                    decoration: const InputDecoration(labelText: 'URL'),
                  ),
                SwitchListTile(
                  title: const Text('启用'),
                  value: enabled,
                  onChanged: (v) => setDialogState(() => enabled = v),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () {
                final name = nameCtrl.text.trim();
                final command = commandCtrl.text.trim();
                final args = argsCtrl.text
                    .trim()
                    .split(RegExp(r'\s+'))
                    .where((e) => e.isNotEmpty)
                    .toList();
                Navigator.pop(
                  dialogContext,
                  MCPServerInfo(
                    name: name,
                    type: type,
                    enabled: enabled,
                    command: type == 'stdio' && command.isNotEmpty ? command : null,
                    url: type == 'http' && urlCtrl.text.trim().isNotEmpty
                        ? urlCtrl.text.trim()
                        : null,
                    args: args,
                  ),
                );
              },
              child: const Text('保存'),
            ),
          ],
        ),
      ),
    );
    return result;
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
            tooltip: '新增服务器',
            onPressed: _addServer,
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: _addServer,
        tooltip: '新增服务器',
        child: const Icon(Icons.add),
      ),
      body: mcpState.when(
        data: (data) {
          if (data.servers.isEmpty) {
            return Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.dns_outlined, size: 56, color: Colors.grey),
                  const SizedBox(height: 12),
                  const Text('还没有 MCP 服务器'),
                  const SizedBox(height: 8),
                  FilledButton.icon(
                    onPressed: _addServer,
                    icon: const Icon(Icons.add),
                    label: const Text('添加第一个服务器'),
                  ),
                ],
              ),
            );
          }
          return ListView.builder(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 88),
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
                            Expanded(
                              child: Text(
                                server.name,
                                style: const TextStyle(
                                  fontSize: 16,
                                  fontWeight: FontWeight.bold,
                                ),
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
                            IconButton(
                              icon: const Icon(Icons.edit_outlined),
                              tooltip: '编辑',
                              onPressed: () => _editServer(server),
                            ),
                            IconButton(
                              icon: const Icon(Icons.delete_outline),
                              tooltip: '删除',
                              onPressed: () => _removeServer(server),
                            ),
                          ],
                        ),
                        const SizedBox(height: 4),
                        Text(
                          server.type == 'stdio'
                              ? '类型: stdio'
                              : '类型: ${server.type}（暂不支持）',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                        if (server.command != null)
                          Text('命令: ${server.command} ${server.args.join(' ')}',
                              style: Theme.of(context).textTheme.bodySmall),
                        if (server.url != null)
                          Text('URL: ${server.url}',
                              style: Theme.of(context).textTheme.bodySmall),
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