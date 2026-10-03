import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../platform/agent_channel.dart';
import '../widgets/glass_card.dart';

class TerminalScreen extends ConsumerStatefulWidget {
  const TerminalScreen({super.key});

  @override
  ConsumerState<TerminalScreen> createState() => _TerminalScreenState();
}

class _TerminalScreenState extends ConsumerState<TerminalScreen> {
  final TextEditingController _commandController = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  final List<String> _output = [];
  bool _isRunning = false;
  bool _rootfsReady = false;
  bool _installing = false;
  List<Map<String, dynamic>> _profiles = [];
  String _currentId = 'alpine';
  String _selectedId = 'alpine';
  StreamSubscription<String>? _commandSubscription;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _loadProfiles();
    });
  }

  @override
  void dispose() {
    _commandController.dispose();
    _scrollController.dispose();
    _commandSubscription?.cancel();
    super.dispose();
  }

  Future<void> _loadProfiles() async {
    final current = await AgentChannel.currentRootfs();
    final profiles = await AgentChannel.rootfsProfiles();
    if (!mounted) return;
    setState(() {
      _currentId = current;
      _selectedId = _selectedId;
      _profiles = profiles;
      _rootfsReady = _isInstalled(current);
    });
  }

  bool _isInstalled(String id) {
    for (final p in _profiles) {
      if (p['id'] == id) return p['installed'] as bool? ?? false;
    }
    return false;
  }

  bool get _selectedInstalled => _isInstalled(_selectedId);

  String _profileLabel(Map<String, dynamic> p) {
    final id = p['id'];
    final installed = p['installed'] as bool? ?? false;
    final isCurrent = id == _currentId;
    return '${p['name']}${isCurrent ? ' · 当前' : (installed ? ' · 已装' : ' · 未装')}';
  }

  Future<void> _installRootfs() async {
    setState(() => _installing = true);
    final msg = await AgentChannel.installRootfs(_selectedId);
    if (!mounted) return;
    setState(() => _installing = false);
    await _loadProfiles();
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _useRootfs() async {
    await AgentChannel.setCurrentRootfs(_selectedId);
    if (!mounted) return;
    await _loadProfiles();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('已切换到：$_selectedId')),
    );
  }

  Future<void> _resetRootfs() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('重置镜像'),
        content: Text('删除「$_selectedId」的 rootfs 并清空数据，确认吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('重置'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    final msg = await AgentChannel.resetRootfs(_selectedId);
    if (!mounted) return;
    await _loadProfiles();
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _addCustomRootfs() async {
    final nameCtrl = TextEditingController();
    final urlCtrl = TextEditingController();
    final shaCtrl = TextEditingController();
    final result = await showDialog<({String name, String url, String sha})>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('添加自定义镜像'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: nameCtrl,
                decoration: const InputDecoration(labelText: '名称'),
              ),
              TextField(
                controller: urlCtrl,
                decoration: const InputDecoration(
                  labelText: 'rootfs 下载 URL（tar.gz）',
                ),
              ),
              TextField(
                controller: shaCtrl,
                decoration: const InputDecoration(labelText: 'SHA256（可选）'),
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
            onPressed: () => Navigator.pop(
              dialogContext,
              (
                name: nameCtrl.text.trim(),
                url: urlCtrl.text.trim(),
                sha: shaCtrl.text.trim(),
              ),
            ),
            child: const Text('添加'),
          ),
        ],
      ),
    );
    if (result == null || result.name.isEmpty || result.url.isEmpty) return;
    final id = await AgentChannel.addRootfsProfile(
      name: result.name,
      url: result.url,
      sha256: result.sha,
    );
    if (!mounted) return;
    await _loadProfiles();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('已添加：$id')),
    );
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
        );
      }
    });
  }

  Future<void> _startContainer() async {
    final ok = await AgentChannel.startContainer();
    if (mounted) {
      setState(() {
        _isRunning = ok;
      });
      if (!ok) {
        final err = await AgentChannel.containerError();
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('启动失败：${err ?? '未知原因'}')),
        );
        _loadProfiles();
      }
    }
  }
  Future<void> _stopContainer() async {
    await AgentChannel.stopContainer();
    if (mounted) {
      setState(() {
        _isRunning = false;
      });
    }
  }

  Future<void> _executeCommand() async {
    final command = _commandController.text.trim();
    if (command.isEmpty || !_isRunning) return;

    setState(() {
      _output.add('\$ $command');
    });
    _commandController.clear();
    _scrollToBottom();

    _commandSubscription?.cancel();
    _commandSubscription = AgentChannel.executeCommand(command).listen(
      (line) {
        setState(() {
          _output.add(line);
        });
        _scrollToBottom();
      },
      onDone: () {
        setState(() {
          _output.add('');
        });
        _scrollToBottom();
      },
      onError: (Object e) {
        setState(() {
          _output.add('错误: $e');
        });
        _scrollToBottom();
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('终端容器'),
        actions: [
          IconButton(
            icon: Icon(_isRunning ? Icons.stop : Icons.play_arrow),
            onPressed: () {
              if (_isRunning) {
                _stopContainer();
              } else {
                _startContainer();
              }
            },
          ),
        ],
      ),
      body: Column(
        children: [
          // 镜像管理条
          Container(
            color: Theme.of(context)
                .colorScheme
                .surfaceContainerHighest
                .withOpacity(0.3),
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(Icons.dns_outlined, size: 16),
                    const SizedBox(width: 6),
                    const Text(
                      'Linux 环境（镜像）',
                      style: TextStyle(fontSize: 13, fontWeight: FontWeight.bold),
                    ),
                    const Spacer(),
                    if (!_installing)
                      IconButton(
                        icon: const Icon(Icons.add_box_outlined),
                        tooltip: '自定义镜像',
                        onPressed: _addCustomRootfs,
                      ),
                  ],
                ),
                Row(
                  children: [
                    Expanded(
                      child: DropdownButton<String>(
                        value: _selectedId,
                        isExpanded: true,
                        items: [
                          for (final p in _profiles)
                            DropdownMenuItem(
                              value: p['id'] as String,
                              child: Text(
                                _profileLabel(p),
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                        ],
                        onChanged: _installing
                            ? null
                            : (v) {
                                if (v != null) {
                                  setState(() => _selectedId = v);
                                }
                              },
                      ),
                    ),
                    const SizedBox(width: 8),
                    if (_installing)
                      const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    else
                      FilledButton(
                        onPressed: _selectedInstalled ? _useRootfs : _installRootfs,
                        child: Text(_selectedInstalled ? '使用' : '安装'),
                      ),
                    const SizedBox(width: 4),
                    IconButton(
                      icon: const Icon(Icons.refresh),
                      tooltip: '重置镜像',
                      onPressed:
                          _selectedInstalled && !_installing ? _resetRootfs : null,
                    ),
                  ],
                ),
                if (_installing)
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Text(
                      '正在下载并安装「$_selectedId」...',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ),
              ],
            ),
          ),
          Expanded(
            child: Container(
              color: Colors.black87,
              padding: const EdgeInsets.all(8),
              child: ListView.builder(
                controller: _scrollController,
                itemCount: _output.length,
                itemBuilder: (context, index) {
                  return Text(
                    _output[index],
                    style: const TextStyle(
                      color: Colors.white,
                      fontFamily: 'monospace',
                      fontSize: 14,
                    ),
                  );
                },
              ),
            ),
          ),
          const AnimatedDivider(),
          Padding(
            padding: const EdgeInsets.all(8.0),
            child: Row(
              children: [
                Expanded(
                  child: MaskedInput(
                    controller: _commandController,
                    hintText: '输入命令...',
                    onSubmitted: _executeCommand,
                  ),
                ),
                const SizedBox(width: 8),
                GlassCard(
                  onTap: _executeCommand,
                  child: const Icon(
                    Icons.play_arrow,
                    color: Colors.white,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
