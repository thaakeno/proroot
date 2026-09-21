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

  @override
  Widget build(BuildContext context) {
    final s = controller.snapshot;
    final active = {
      RuntimePhase.downloading,
      RuntimePhase.extracting,
      RuntimePhase.provisioning,
    }.contains(s.phase);

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
                          Icon(s.installed ? Icons.check_circle_rounded : Icons.downloading_rounded),
                          const SizedBox(width: 12),
                          Expanded(child: Text(s.message, style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700))),
                        ],
                      ),
                      const SizedBox(height: 20),
                      LinearProgressIndicator(
                        value: active ? (s.progress <= 0 ? null : s.progress) : s.installed ? 1 : 0,
                        minHeight: 10,
                        borderRadius: BorderRadius.circular(99),
                      ),
                      const SizedBox(height: 12),
                      Row(
                        children: [
                          Text('${_bytes(s.downloadedBytes)} / ${_bytes(s.totalBytes)}'),
                          const Spacer(),
                          if (s.speedBytesPerSecond > 0) Text('${_bytes(s.speedBytesPerSecond)}/s'),
                        ],
                      ),
                      if (s.detail case final detail?) ...[
                        const SizedBox(height: 12),
                        Text(detail, style: Theme.of(context).textTheme.bodySmall),
                      ],
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 18),
              const _Stage(title: 'Base system', subtitle: 'Debian 13 ARM64 with verified checksum', icon: Icons.inventory_2_outlined),
              const SizedBox(height: 10),
              const _Stage(title: 'GPU stack', subtitle: 'Mesa Freedreno + Turnip for Adreno 840', icon: Icons.memory_rounded),
              const SizedBox(height: 10),
              const _Stage(title: 'Desktop', subtitle: 'KDE Plasma + Anland Wayland + XWayland', icon: Icons.desktop_windows_outlined),
              const SizedBox(height: 10),
              const _Stage(title: 'Applications', subtitle: 'Brave, Firefox, Konsole, VS Code, Dolphin, Kate and tools', icon: Icons.apps_rounded),
              const SizedBox(height: 22),
              FilledButton.icon(
                onPressed: controller.busy || s.installed ? null : controller.install,
                icon: const Icon(Icons.download_rounded),
                label: Text(s.installed ? 'Linux is installed' : 'Install complete Linux PC'),
              ),
              if (s.installed) ...[
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

class _Stage extends StatelessWidget {
  const _Stage({required this.title, required this.subtitle, required this.icon});
  final String title;
  final String subtitle;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
        leading: CircleAvatar(child: Icon(icon)),
        title: Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
        subtitle: Text(subtitle),
      ),
    );
  }
}
