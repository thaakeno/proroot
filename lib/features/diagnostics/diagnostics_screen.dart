import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/state/runtime_controller.dart';

class DiagnosticsScreen extends StatefulWidget {
  const DiagnosticsScreen({required this.controller, super.key});
  final RuntimeController controller;

  @override
  State<DiagnosticsScreen> createState() => _DiagnosticsScreenState();
}

class _DiagnosticsScreenState extends State<DiagnosticsScreen> {
  late Future<Map<String, dynamic>> _future;

  @override
  void initState() {
    super.initState();
    _future = widget.controller.bridge.diagnostics();
  }

  void _reload() {
    setState(() => _future = widget.controller.bridge.diagnostics());
  }

  String _pretty(Map<String, dynamic> data) {
    final buffer = StringBuffer();
    data.forEach((key, value) {
      if (key == 'logs') return;
      buffer.writeln('$key: $value');
    });
    final logs = data['logs'];
    if (logs is Map) {
      for (final entry in logs.entries) {
        buffer
          ..writeln()
          ..writeln('===== ${entry.key} =====')
          ..writeln(entry.value);
      }
    }
    return buffer.toString();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Diagnostics'),
        actions: [
          IconButton(
            onPressed: _reload,
            tooltip: 'Refresh',
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      body: FutureBuilder<Map<String, dynamic>>(
        future: _future,
        builder: (context, snapshot) {
          if (!snapshot.hasData && snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return Center(child: Text('Diagnostics failed: ${snapshot.error}'));
          }

          final data = snapshot.data ?? const {};
          final text = _pretty(data);
          final logs = data['logs'];

          return ListView(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 40),
            children: [
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(18),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('Runtime', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800)),
                      const SizedBox(height: 14),
                      ...data.entries
                          .where((entry) => entry.key != 'logs' && entry.key != 'status')
                          .map((entry) => Padding(
                                padding: const EdgeInsets.only(bottom: 8),
                                child: Row(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    SizedBox(
                                      width: 132,
                                      child: Text(entry.key, style: Theme.of(context).textTheme.labelMedium),
                                    ),
                                    Expanded(child: SelectableText('${entry.value}')),
                                  ],
                                ),
                              )),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 14),
              if (logs is Map && logs.isNotEmpty)
                ...logs.entries.map(
                  (entry) => Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: ExpansionTile(
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
                      collapsedShape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
                      backgroundColor: Theme.of(context).cardTheme.color,
                      collapsedBackgroundColor: Theme.of(context).cardTheme.color,
                      title: Text('${entry.key}', style: const TextStyle(fontWeight: FontWeight.w700)),
                      children: [
                        Padding(
                          padding: const EdgeInsets.fromLTRB(18, 0, 18, 18),
                          child: SelectableText(
                            '${entry.value}',
                            style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              const SizedBox(height: 8),
              FilledButton.tonalIcon(
                onPressed: () async {
                  final messenger = ScaffoldMessenger.of(context);
                  await Clipboard.setData(ClipboardData(text: text));
                  if (!mounted) {
                    return;
                  }
                  messenger.showSnackBar(
                    const SnackBar(content: Text('Diagnostics copied')),
                  );
                },
                icon: const Icon(Icons.copy_all_rounded),
                label: const Text('Copy diagnostics'),
              ),
            ],
          );
        },
      ),
    );
  }
}
