import 'package:flutter/material.dart';

import '../../core/models/runtime_snapshot.dart';
import '../../core/state/runtime_controller.dart';

class InstallScreen extends StatelessWidget {
  const InstallScreen({required this.controller, super.key});

  final RuntimeController controller;

  String _bytes(int value) {
    if (value <= 0) return '—';
    const units = ['B', 'KB', 'MB', 'GB'];
    var size = value.toDouble();
    var unit = 0;
    while (size >= 1024 && unit < units.length - 1) {
      size /= 1024;
      unit++;
    }
    return '${size.toStringAsFixed(unit > 1 ? 1 : 0)} ${units[unit]}';
  }

  String _eta(RuntimeSnapshot snapshot) {
    final speed = snapshot.speedBytesPerSecond;
    final remaining = snapshot.totalBytes - snapshot.downloadedBytes;
    if (speed <= 0 || remaining <= 0) {
      return '';
    }

    final seconds = (remaining / speed).ceil();
    if (seconds < 60) {
      return '~${seconds}s left';
    }
    final minutes = (seconds / 60).ceil();
    if (minutes < 60) {
      return '~${minutes}m left';
    }
    final hours = seconds ~/ 3600;
    final restMinutes = ((seconds % 3600) / 60).ceil();
    return '~${hours}h ${restMinutes}m left';
  }

  _StageState _stateFor(
    RuntimeSnapshot snapshot, {
    required double start,
    required double end,
  }) {
    if (snapshot.installed) {
      return _StageState.done;
    }
    if (snapshot.progress >= end) {
      return _StageState.done;
    }
    if (snapshot.progress >= start && controller.busy) {
      return _StageState.active;
    }
    return _StageState.pending;
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = controller.snapshot;
    final active = {
      RuntimePhase.downloading,
      RuntimePhase.extracting,
      RuntimePhase.provisioning,
    }.contains(snapshot.phase);
    final percent = (snapshot.progress * 100).round();
    final eta = _eta(snapshot);

    return CustomScrollView(
      slivers: [
        const SliverAppBar.large(title: Text('Setup')),
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 120),
          sliver: SliverList.list(
            children: [
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(22),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          _StatusIcon(snapshot: snapshot),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Text(
                              snapshot.message,
                              style: Theme.of(context)
                                  .textTheme
                                  .titleMedium
                                  ?.copyWith(fontWeight: FontWeight.w700),
                            ),
                          ),
                          if (active)
                            Text(
                              '$percent%',
                              style: Theme.of(context)
                                  .textTheme
                                  .titleSmall
                                  ?.copyWith(fontWeight: FontWeight.w800),
                            ),
                        ],
                      ),
                      const SizedBox(height: 20),
                      LinearProgressIndicator(
                        value: active
                            ? (snapshot.progress <= 0
                                ? null
                                : snapshot.progress)
                            : snapshot.installed
                                ? 1
                                : 0,
                        minHeight: 10,
                        borderRadius: BorderRadius.circular(99),
                      ),
                      const SizedBox(height: 12),
                      if (snapshot.phase == RuntimePhase.downloading)
                        Row(
                          children: [
                            Expanded(
                              child: Text(
                                '${_bytes(snapshot.downloadedBytes)} / '
                                '${_bytes(snapshot.totalBytes)}',
                              ),
                            ),
                            if (snapshot.speedBytesPerSecond > 0) ...[
                              Text(
                                '${_bytes(snapshot.speedBytesPerSecond)}/s',
                              ),
                              if (eta.isNotEmpty) ...[
                                const SizedBox(width: 10),
                                Text(
                                  eta,
                                  style: Theme.of(context).textTheme.bodySmall,
                                ),
                              ],
                            ],
                          ],
                        )
                      else if (active)
                        Text(
                          '$percent% of the complete Linux environment',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      if (snapshot.detail case final detail?) ...[
                        const SizedBox(height: 12),
                        Text(
                          detail,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 18),
              _Stage(
                title: 'Base system',
                subtitle: 'Debian 13 ARM64 with verified checksum',
                icon: Icons.inventory_2_outlined,
                state: _stateFor(snapshot, start: 0, end: 0.46),
              ),
              const SizedBox(height: 10),
              _Stage(
                title: 'Desktop',
                subtitle: 'KDE Plasma + Anland Wayland + XWayland',
                icon: Icons.desktop_windows_outlined,
                state: _stateFor(snapshot, start: 0.46, end: 0.74),
              ),
              const SizedBox(height: 10),
              _Stage(
                title: 'Applications',
                subtitle:
                    'Brave, Firefox, Konsole, VS Code, Dolphin, Kate and tools',
                icon: Icons.apps_rounded,
                state: _stateFor(snapshot, start: 0.74, end: 0.89),
              ),
              const SizedBox(height: 10),
              _Stage(
                title: 'GPU stack',
                subtitle: 'Mesa Freedreno + Turnip for Adreno 840',
                icon: Icons.memory_rounded,
                state: _stateFor(snapshot, start: 0.89, end: 0.94),
              ),
              const SizedBox(height: 10),
              _Stage(
                title: 'Verification',
                subtitle: 'GPU, Linux ABI, browsers and atomic activation',
                icon: Icons.verified_outlined,
                state: _stateFor(snapshot, start: 0.94, end: 1),
              ),
              const SizedBox(height: 22),
              FilledButton.icon(
                onPressed: controller.busy || snapshot.installed
                    ? null
                    : controller.install,
                icon: const Icon(Icons.download_rounded),
                label: Text(
                  snapshot.installed
                      ? 'Linux is installed'
                      : 'Install complete Linux PC',
                ),
              ),
              if (snapshot.installed) ...[
                const SizedBox(height: 10),
                OutlinedButton.icon(
                  onPressed: controller.busy ? null : controller.reset,
                  icon: const Icon(Icons.restart_alt_rounded),
                  label: const Text('Reinstall from scratch'),
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

enum _StageState {
  pending,
  active,
  done,
}

class _StatusIcon extends StatelessWidget {
  const _StatusIcon({required this.snapshot});

  final RuntimeSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    if (snapshot.installed) {
      return const Icon(Icons.check_circle_rounded);
    }
    if (snapshot.phase == RuntimePhase.failed) {
      return Icon(
        Icons.error_rounded,
        color: Theme.of(context).colorScheme.error,
      );
    }
    return const Icon(Icons.downloading_rounded);
  }
}

class _Stage extends StatelessWidget {
  const _Stage({
    required this.title,
    required this.subtitle,
    required this.icon,
    required this.state,
  });

  final String title;
  final String subtitle;
  final IconData icon;
  final _StageState state;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final active = state == _StageState.active;
    final done = state == _StageState.done;

    return Card(
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 18,
          vertical: 8,
        ),
        leading: CircleAvatar(
          backgroundColor: active
              ? scheme.primaryContainer
              : done
                  ? scheme.secondaryContainer
                  : scheme.surfaceContainerHighest,
          child: active
              ? SizedBox(
                  width: 19,
                  height: 19,
                  child: CircularProgressIndicator(
                    strokeWidth: 2.5,
                    color: scheme.onPrimaryContainer,
                  ),
                )
              : Icon(done ? Icons.check_rounded : icon),
        ),
        title: Text(
          title,
          style: TextStyle(
            fontWeight: FontWeight.w700,
            color: state == _StageState.pending
                ? scheme.onSurfaceVariant
                : scheme.onSurface,
          ),
        ),
        subtitle: Text(subtitle),
      ),
    );
  }
}
