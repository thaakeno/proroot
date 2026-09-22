import 'dart:convert';

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

  String _fullText(Map<String, dynamic> data) {
    final summary = Map<String, dynamic>.from(data)..remove('logs');
    final buffer = StringBuffer()
      ..writeln('===== runtime diagnostics =====')
      ..writeln(const JsonEncoder.withIndent('  ').convert(summary));

    final logs = data['logs'];
    if (logs is Map && logs.isNotEmpty) {
      for (final entry in logs.entries) {
        buffer
          ..writeln()
          ..writeln('===== ${entry.key} =====')
          ..writeln(entry.value);
      }
    } else {
      buffer
        ..writeln()
        ..writeln('===== logs =====')
        ..writeln('No persistent log files found.');
    }
    return buffer.toString();
  }

  String _size(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) {
      return '${(bytes / 1024).toStringAsFixed(1)} KB';
    }
    return '${(bytes / 1024 / 1024).toStringAsFixed(1)} MB';
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
          if (!snapshot.hasData &&
              snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: SelectableText(
                  'Diagnostics failed:\n${snapshot.error}',
                ),
              ),
            );
          }

          final data = snapshot.data ?? const <String, dynamic>{};
          final logs = data['logs'];
          final crashDumps = data['prorootCrashDumps'];
          final crashDumpEntries =
              crashDumps is Map ? crashDumps.entries.toList(growable: false) : const [];
          final logEntries =
              logs is Map ? logs.entries.toList(growable: false) : const [];

          return ListView(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 40),
            children: [
              FilledButton.icon(
                onPressed: () async {
                  final messenger = ScaffoldMessenger.of(context);
                  final fullText = _fullText(data);
                  await Clipboard.setData(ClipboardData(text: fullText));
                  if (!mounted) return;
                  messenger.showSnackBar(
                    SnackBar(
                      content: Text(
                        'Copied full diagnostics (${_size(fullText.length)})',
                      ),
                    ),
                  );
                },
                icon: const Icon(Icons.copy_all_rounded),
                label: const Text('Copy full diagnostics + logs'),
              ),
              const SizedBox(height: 14),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(18),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'Runtime',
                        style: TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      const SizedBox(height: 14),
                      ...data.entries
                          .where(
                            (entry) =>
                                entry.key != 'logs' &&
                                entry.key != 'probes' &&
                                entry.key != 'prorootCrashDumps',
                          )
                          .map(
                            (entry) => Padding(
                              padding: const EdgeInsets.only(bottom: 9),
                              child: Row(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  SizedBox(
                                    width: 138,
                                    child: Text(
                                      entry.key,
                                      style:
                                          Theme.of(context).textTheme.labelMedium,
                                    ),
                                  ),
                                  Expanded(
                                    child: SelectableText('${entry.value}'),
                                  ),
                                ],
                              ),
                            ),
                          ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 14),
              if (data['probes'] case final Map probes when probes.isNotEmpty)
                Card(
                  child: ExpansionTile(
                    title: const Text(
                      'Runtime probes',
                      style: TextStyle(fontWeight: FontWeight.w700),
                    ),
                    children: [
                      Padding(
                        padding: const EdgeInsets.fromLTRB(18, 0, 18, 18),
                        child: SelectableText(
                          const JsonEncoder.withIndent('  ').convert(probes),
                          style: const TextStyle(
                            fontFamily: 'monospace',
                            fontSize: 12,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              if (data['probes'] case final Map probes when probes.isNotEmpty)
                const SizedBox(height: 14),
              if (crashDumpEntries.isNotEmpty) ...[
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(18),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'ProRoot crash maps captured',
                          style: TextStyle(fontWeight: FontWeight.w800),
                        ),
                        const SizedBox(height: 8),
                        ...crashDumpEntries.map((entry) {
                          final value = entry.value;
                          final bytes = value is Map ? value['bytes'] : null;
                          return Text(
                            bytes is num
                                ? '${entry.key} • ${_size(bytes.toInt())}'
                                : '${entry.key}',
                          );
                        }),
                        const SizedBox(height: 8),
                        Text(
                          'The full maps are included by Copy full diagnostics. '
                          'They are not rendered here to keep this screen stable.',
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 14),
              ],
              Text(
                'Full logs',
                style: Theme.of(context)
                    .textTheme
                    .titleMedium
                    ?.copyWith(fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 10),
              if (logEntries.isEmpty)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(18),
                    child: Text(
                      'No persistent logs exist yet. New builds keep the full '
                      'installation failure and command output here even after '
                      'the app restarts.',
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ),
                )
              else
                ...logEntries.map(
                  (entry) => Padding(
                    padding: const EdgeInsets.only(bottom: 10),
                    child: Card(
                      clipBehavior: Clip.antiAlias,
                      child: ExpansionTile(
                        initiallyExpanded:
                            entry.key == 'last-install-failure.log',
                        title: Text(
                          '${entry.key}',
                          style: const TextStyle(fontWeight: FontWeight.w700),
                        ),
                        subtitle: Text(
                          _size('${entry.value}'.length),
                        ),
                        children: [
                          Container(
                            width: double.infinity,
                            padding: const EdgeInsets.fromLTRB(18, 0, 18, 18),
                            child: Builder(
                              builder: (context) {
                                final value = '${entry.value}';
                                final preview = value.length > 12000
                                    ? '[showing last 12000 characters]\n'
                                        '${value.substring(value.length - 12000)}'
                                    : value;
                                return SelectableText(
                                  preview,
                                  style: const TextStyle(
                                    fontFamily: 'monospace',
                                    fontSize: 12,
                                  ),
                                );
                              },
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              const SizedBox(height: 8),
              OutlinedButton.icon(
                onPressed: _reload,
                icon: const Icon(Icons.refresh_rounded),
                label: const Text('Refresh diagnostics'),
              ),
            ],
          );
        },
      ),
    );
  }
}
