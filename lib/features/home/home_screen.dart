import 'package:flutter/material.dart';
import '../../core/state/runtime_controller.dart';

class HomeScreen extends StatelessWidget {
  const HomeScreen({required this.controller, required this.onOpenDesktop, super.key});
  final RuntimeController controller;
  final VoidCallback onOpenDesktop;

  @override
  Widget build(BuildContext context) {
    final snapshot = controller.snapshot;
    final scheme = Theme.of(context).colorScheme;

    return CustomScrollView(
      slivers: [
        const SliverAppBar.large(title: Text('Linux PC')),
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 120),
          sliver: SliverList.list(
            children: [
              Container(
                padding: const EdgeInsets.all(24),
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    colors: [scheme.primaryContainer, scheme.tertiaryContainer],
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                  ),
                  borderRadius: BorderRadius.circular(28),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Container(
                          width: 46,
                          height: 46,
                          decoration: BoxDecoration(
                            color: scheme.surface.withValues(alpha: .72),
                            borderRadius: BorderRadius.circular(14),
                          ),
                          child: const Icon(Icons.computer),
                        ),
                        const SizedBox(width: 14),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text('Debian 13 · KDE Plasma', style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
                              Text(snapshot.running ? 'Running · direct Adreno 840 graphics' : snapshot.message),
                            ],
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 28),
                    Row(
                      children: [
                        _Metric(label: 'Display', value: '${controller.refreshRate} Hz'),
                        const SizedBox(width: 12),
                        const _Metric(label: 'GPU', value: 'Adreno 840'),
                        const SizedBox(width: 12),
                        const _Metric(label: 'Runtime', value: 'proroot'),
                      ],
                    ),
                    const SizedBox(height: 24),
                    Row(
                      children: [
                        Expanded(
                          child: FilledButton.icon(
                            onPressed: controller.busy
                                ? null
                                : snapshot.running
                                    ? onOpenDesktop
                                    : snapshot.installed
                                        ? () {
                                            onOpenDesktop();
                                            controller.start();
                                          }
                                        : controller.install,
                            icon: Icon(snapshot.running ? Icons.fullscreen : snapshot.installed ? Icons.play_arrow_rounded : Icons.download_rounded),
                            label: Text(snapshot.running ? 'Open desktop' : snapshot.installed ? 'Start Linux' : 'Install Linux'),
                          ),
                        ),
                        if (snapshot.running) ...[
                          const SizedBox(width: 12),
                          IconButton.filledTonal(
                            tooltip: 'Stop Linux',
                            onPressed: controller.stop,
                            icon: const Icon(Icons.stop_rounded),
                          ),
                        ],
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 22),
              Text('Quick launch', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 12),
              _AppGrid(controller: controller, onOpenDesktop: onOpenDesktop),
              const SizedBox(height: 22),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: Row(
                    children: [
                      Icon(Icons.bolt_rounded, color: scheme.primary),
                      const SizedBox(width: 14),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const Text('Native ARM64 userspace', style: TextStyle(fontWeight: FontWeight.w800)),
                            Text('No CPU emulation. Freedreno/Turnip talks directly to KGSL.', style: TextStyle(color: scheme.onSurfaceVariant)),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _Metric extends StatelessWidget {
  const _Metric({required this.label, required this.value});
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surface.withValues(alpha: .62),
          borderRadius: BorderRadius.circular(16),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(value, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800)),
            const SizedBox(height: 2),
            Text(label, style: Theme.of(context).textTheme.labelSmall),
          ],
        ),
      ),
    );
  }
}

class _AppGrid extends StatelessWidget {
  const _AppGrid({required this.controller, required this.onOpenDesktop});

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
        childAspectRatio: 1.22,
        mainAxisSpacing: 12,
        crossAxisSpacing: 12,
      ),
      itemBuilder: (context, index) {
        final item = apps[index];
        return Card(
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: controller.snapshot.installed
                ? () async {
                    onOpenDesktop();
                    try {
                      await controller.launchApp(item.$3);
                    } catch (error) {
                      if (!context.mounted) return;
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(content: Text('Could not launch ${item.$2}: $error')),
                      );
                    }
                  }
                : null,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Icon(item.$1),
                  Text(item.$2, style: const TextStyle(fontWeight: FontWeight.w800)),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}
