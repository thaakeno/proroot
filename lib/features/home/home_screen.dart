import 'package:flutter/material.dart';

import '../../core/models/runtime_snapshot.dart';
import '../../core/state/runtime_controller.dart';

class HomeScreen extends StatelessWidget {
  const HomeScreen({
    required this.controller,
    required this.onOpenDesktop,
    required this.onOpenSetup,
    super.key,
  });

  final RuntimeController controller;
  final VoidCallback onOpenDesktop;
  final VoidCallback onOpenSetup;

  bool _installing(RuntimeSnapshot snapshot) => {
        RuntimePhase.downloading,
        RuntimePhase.extracting,
        RuntimePhase.provisioning,
      }.contains(snapshot.phase);

  String _bytes(int value) {
    if (value <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB'];
    var size = value.toDouble();
    var unit = 0;
    while (size >= 1024 && unit < units.length - 1) {
      size /= 1024;
      unit++;
    }
    return '${size.toStringAsFixed(unit >= 2 ? 1 : 0)} ${units[unit]}';
  }

  String _duration(int seconds) {
    if (seconds < 60) return '${seconds}s';
    final hours = seconds ~/ 3600;
    final minutes = (seconds % 3600) ~/ 60;
    final secs = seconds % 60;
    if (hours > 0) return '${hours}h ${minutes}m';
    if (minutes > 0 && secs > 0) return '${minutes}m ${secs}s';
    return '${minutes}m';
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = controller.snapshot;
    final installing = _installing(snapshot);
    final scheme = Theme.of(context).colorScheme;

    return CustomScrollView(
      slivers: [
        const SliverAppBar.large(title: Text('Linux PC')),
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 120),
          sliver: SliverList.list(
            children: [
              Card(
                clipBehavior: Clip.antiAlias,
                child: Padding(
                  padding: const EdgeInsets.all(22),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Container(
                            width: 50,
                            height: 50,
                            decoration: BoxDecoration(
                              color: scheme.primaryContainer,
                              borderRadius: BorderRadius.circular(16),
                            ),
                            child: Icon(
                              Icons.desktop_windows_rounded,
                              color: scheme.onPrimaryContainer,
                            ),
                          ),
                          const SizedBox(width: 14),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  'Debian 13',
                                  style: Theme.of(context)
                                      .textTheme
                                      .titleLarge
                                      ?.copyWith(fontWeight: FontWeight.w800),
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  snapshot.running
                                      ? 'KDE Plasma is running'
                                      : snapshot.message,
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(
                                    color: scheme.onSurfaceVariant,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          _StatusPill(snapshot: snapshot),
                        ],
                      ),
                      if (installing) ...[
                        const SizedBox(height: 24),
                        _InstallProgress(
                          snapshot: snapshot,
                          bytes: _bytes,
                          elapsed: _duration(snapshot.elapsedSeconds),
                          eta: snapshot.etaSeconds == null
                              ? 'Calculating…'
                              : '~${_duration(snapshot.etaSeconds!)}',
                        ),
                        const SizedBox(height: 18),
                        SizedBox(
                          width: double.infinity,
                          child: FilledButton.icon(
                            onPressed: onOpenSetup,
                            icon: const Icon(Icons.downloading_rounded),
                            label: const Text('View setup'),
                          ),
                        ),
                        const SizedBox(height: 10),
                        Text(
                          'Setup keeps running if you leave this screen.',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ] else if (snapshot.phase == RuntimePhase.failed) ...[
                        const SizedBox(height: 20),
                        Container(
                          width: double.infinity,
                          padding: const EdgeInsets.all(14),
                          decoration: BoxDecoration(
                            color: scheme.errorContainer,
                            borderRadius: BorderRadius.circular(16),
                          ),
                          child: Text(
                            snapshot.detail?.split('\n').firstOrNull ??
                                'The installation stopped. Open Setup or Diagnostics for the full error.',
                            style: TextStyle(color: scheme.onErrorContainer),
                          ),
                        ),
                        const SizedBox(height: 16),
                        Row(
                          children: [
                            Expanded(
                              child: FilledButton.icon(
                                onPressed: controller.install,
                                icon: const Icon(Icons.refresh_rounded),
                                label: const Text('Retry install'),
                              ),
                            ),
                            const SizedBox(width: 10),
                            OutlinedButton(
                              onPressed: onOpenSetup,
                              child: const Text('Setup'),
                            ),
                          ],
                        ),
                      ] else ...[
                        const SizedBox(height: 22),
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: [
                            _InfoChip(
                              icon: Icons.speed_rounded,
                              label: '${controller.refreshRate} Hz',
                            ),
                            const _InfoChip(
                              icon: Icons.memory_rounded,
                              label: 'Adreno 840',
                            ),
                            const _InfoChip(
                              icon: Icons.terminal_rounded,
                              label: 'proroot',
                            ),
                          ],
                        ),
                        const SizedBox(height: 20),
                        SizedBox(
                          width: double.infinity,
                          child: FilledButton.icon(
                            onPressed: snapshot.running
                                ? onOpenDesktop
                                : snapshot.installed
                                    ? () {
                                        onOpenDesktop();
                                        controller.start();
                                      }
                                    : controller.install,
                            icon: Icon(
                              snapshot.running
                                  ? Icons.fullscreen_rounded
                                  : snapshot.installed
                                      ? Icons.play_arrow_rounded
                                      : Icons.download_rounded,
                            ),
                            label: Text(
                              snapshot.running
                                  ? 'Open desktop'
                                  : snapshot.installed
                                      ? 'Start Linux'
                                      : 'Install Linux',
                            ),
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
              if (snapshot.installed) ...[
                const SizedBox(height: 26),
                Text(
                  'Quick launch',
                  style: Theme.of(context)
                      .textTheme
                      .titleMedium
                      ?.copyWith(fontWeight: FontWeight.w800),
                ),
                const SizedBox(height: 12),
                _AppGrid(
                  controller: controller,
                  onOpenDesktop: onOpenDesktop,
                ),
              ] else if (!installing &&
                  snapshot.phase != RuntimePhase.failed) ...[
                const SizedBox(height: 18),
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(18),
                    child: Row(
                      children: [
                        Icon(
                          Icons.info_outline_rounded,
                          color: scheme.primary,
                        ),
                        const SizedBox(width: 14),
                        const Expanded(
                          child: Text(
                            'The desktop and app launcher appear after setup finishes.',
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

class _InstallProgress extends StatelessWidget {
  const _InstallProgress({
    required this.snapshot,
    required this.bytes,
    required this.elapsed,
    required this.eta,
  });

  final RuntimeSnapshot snapshot;
  final String Function(int) bytes;
  final String elapsed;
  final String eta;

  @override
  Widget build(BuildContext context) {
    final percent = (snapshot.progress * 100).round();
    final stagePercent = snapshot.stageProgress == null
        ? null
        : (snapshot.stageProgress! * 100).round();
    final hasStage = snapshot.stageProgress != null ||
        (snapshot.stageDetail?.isNotEmpty ?? false);
    final hasStageBytes = snapshot.stageTotalBytes > 0;
    final hasItems = snapshot.totalItems > 0;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                snapshot.message,
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
            ),
            Text(
              '$percent%',
              style: const TextStyle(fontWeight: FontWeight.w800),
            ),
          ],
        ),
        const SizedBox(height: 10),
        LinearProgressIndicator(
          value: snapshot.progress > 0 ? snapshot.progress : null,
          minHeight: 9,
          borderRadius: BorderRadius.circular(99),
        ),
        const SizedBox(height: 8),
        Text(
          'Overall installation progress',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        if (hasStage) ...[
          const SizedBox(height: 16),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(16),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Expanded(
                      child: Text(
                        'Current task',
                        style: TextStyle(fontWeight: FontWeight.w700),
                      ),
                    ),
                    if (stagePercent != null)
                      Text(
                        '$stagePercent%',
                        style: const TextStyle(fontWeight: FontWeight.w800),
                      ),
                  ],
                ),
                if (snapshot.stageProgress != null) ...[
                  const SizedBox(height: 8),
                  LinearProgressIndicator(
                    value: snapshot.stageProgress,
                    minHeight: 6,
                    borderRadius: BorderRadius.circular(99),
                  ),
                ],
                if (snapshot.stageDetail case final detail?) ...[
                  const SizedBox(height: 8),
                  Text(
                    detail,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
                if (hasStageBytes || hasItems) ...[
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 12,
                    runSpacing: 4,
                    children: [
                      if (hasStageBytes)
                        Text(
                          '${bytes(snapshot.stageDownloadedBytes)} / '
                          '${bytes(snapshot.stageTotalBytes)}',
                        ),
                      if (snapshot.stageSpeedBytesPerSecond > 0)
                        Text(
                          '${bytes(snapshot.stageSpeedBytesPerSecond)}/s',
                        ),
                      if (hasItems)
                        Text(
                          '${snapshot.completedItems} / '
                          '${snapshot.totalItems} items',
                        ),
                    ],
                  ),
                ],
              ],
            ),
          ),
        ],
        const SizedBox(height: 10),
        Wrap(
          spacing: 14,
          runSpacing: 4,
          children: [
            Text('Elapsed $elapsed'),
            Text('ETA $eta'),
          ],
        ),
      ],
    );
  }
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({required this.snapshot});

  final RuntimeSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final (label, icon, background, foreground) = switch (snapshot.phase) {
      RuntimePhase.running => (
          'Running',
          Icons.circle,
          scheme.primaryContainer,
          scheme.onPrimaryContainer,
        ),
      RuntimePhase.downloading ||
      RuntimePhase.extracting ||
      RuntimePhase.provisioning => (
          'Installing',
          Icons.downloading_rounded,
          scheme.secondaryContainer,
          scheme.onSecondaryContainer,
        ),
      RuntimePhase.failed => (
          'Failed',
          Icons.error_outline_rounded,
          scheme.errorContainer,
          scheme.onErrorContainer,
        ),
      RuntimePhase.ready => (
          'Ready',
          Icons.check_circle_outline_rounded,
          scheme.primaryContainer,
          scheme.onPrimaryContainer,
        ),
      _ => (
          'Not installed',
          Icons.circle_outlined,
          scheme.surfaceContainerHighest,
          scheme.onSurfaceVariant,
        ),
    };

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(99),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 13, color: foreground),
          const SizedBox(width: 6),
          Text(
            label,
            style: TextStyle(
              color: foreground,
              fontSize: 12,
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
    );
  }
}

class _InfoChip extends StatelessWidget {
  const _InfoChip({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Chip(
      avatar: Icon(icon, size: 17),
      label: Text(label),
      visualDensity: VisualDensity.compact,
    );
  }
}

class _AppGrid extends StatelessWidget {
  const _AppGrid({
    required this.controller,
    required this.onOpenDesktop,
  });

  final RuntimeController controller;
  final VoidCallback onOpenDesktop;

  static const apps = [
    (Icons.language_rounded, 'Brave', 'brave-browser.desktop'),
    (Icons.public_rounded, 'Firefox', 'firefox-esr.desktop'),
    (Icons.terminal_rounded, 'Konsole', 'org.kde.konsole.desktop'),
    (Icons.code_rounded, 'VS Code', 'code.desktop'),
    (Icons.folder_rounded, 'Dolphin', 'org.kde.dolphin.desktop'),
    (Icons.edit_note_rounded, 'Kate', 'org.kde.kate.desktop'),
  ];

  @override
  Widget build(BuildContext context) {
    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      itemCount: apps.length,
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 3,
        childAspectRatio: 1.12,
        mainAxisSpacing: 10,
        crossAxisSpacing: 10,
      ),
      itemBuilder: (context, index) {
        final item = apps[index];
        return Card(
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: () async {
              onOpenDesktop();
              try {
                await controller.launchApp(item.$3);
              } catch (error) {
                if (!context.mounted) return;
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(
                    content: Text('Could not launch ${item.$2}: $error'),
                  ),
                );
              }
            },
            child: Padding(
              padding: const EdgeInsets.all(15),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Icon(item.$1, size: 27),
                  Text(
                    item.$2,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.w800),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}
