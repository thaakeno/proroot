import 'package:flutter/material.dart';
import '../../core/models/linux_app.dart';
import '../../core/state/runtime_controller.dart';

class AppsScreen extends StatefulWidget {
  const AppsScreen({
    required this.controller,
    required this.onOpenDesktop,
    super.key,
  });

  final RuntimeController controller;
  final VoidCallback onOpenDesktop;

  @override
  State<AppsScreen> createState() => _AppsScreenState();
}

class _AppsScreenState extends State<AppsScreen> {
  late Future<List<LinuxApp>> _apps;

  @override
  void initState() {
    super.initState();
    _apps = widget.controller.desktopApps();
  }

  void _refresh() {
    setState(() => _apps = widget.controller.desktopApps());
  }

  IconData _iconFor(LinuxApp app) {
    final text = '${app.name} ${app.icon ?? ''} ${app.categories.join(' ')}'.toLowerCase();
    if (text.contains('browser') || text.contains('firefox') || text.contains('brave')) {
      return Icons.language_rounded;
    }
    if (text.contains('terminal') || text.contains('konsole')) return Icons.terminal_rounded;
    if (text.contains('code') || text.contains('development')) return Icons.code_rounded;
    if (text.contains('file') || text.contains('dolphin')) return Icons.folder_rounded;
    if (text.contains('office') || text.contains('writer')) return Icons.description_rounded;
    if (text.contains('video') || text.contains('vlc')) return Icons.play_circle_outline_rounded;
    if (text.contains('graphics') || text.contains('gimp')) return Icons.brush_rounded;
    if (text.contains('settings')) return Icons.tune_rounded;
    return Icons.apps_rounded;
  }

  Future<void> _launch(LinuxApp app) async {
    widget.onOpenDesktop();
    try {
      await widget.controller.launchApp(app.id);
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Could not launch ${app.name}: $error')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return CustomScrollView(
      slivers: [
        SliverAppBar.large(
          title: const Text('Apps'),
          actions: [
            IconButton(
              onPressed: _refresh,
              tooltip: 'Refresh apps',
              icon: const Icon(Icons.refresh_rounded),
            ),
          ],
        ),
        FutureBuilder<List<LinuxApp>>(
          future: _apps,
          builder: (context, snapshot) {
            if (snapshot.connectionState != ConnectionState.done) {
              return const SliverFillRemaining(
                hasScrollBody: false,
                child: Center(child: CircularProgressIndicator()),
              );
            }

            final apps = snapshot.data ?? const [];
            if (apps.isEmpty) {
              return SliverFillRemaining(
                hasScrollBody: false,
                child: Center(
                  child: Padding(
                    padding: const EdgeInsets.all(32),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(Icons.apps_outage_rounded, size: 52),
                        const SizedBox(height: 16),
                        Text(
                          widget.controller.snapshot.installed
                              ? 'No desktop applications were found.'
                              : 'Install Linux to see its applications here.',
                          textAlign: TextAlign.center,
                        ),
                      ],
                    ),
                  ),
                ),
              );
            }

            return SliverPadding(
              padding: const EdgeInsets.fromLTRB(20, 4, 20, 120),
              sliver: SliverGrid.builder(
                itemCount: apps.length,
                gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                  maxCrossAxisExtent: 220,
                  mainAxisExtent: 126,
                  mainAxisSpacing: 12,
                  crossAxisSpacing: 12,
                ),
                itemBuilder: (context, index) {
                  final app = apps[index];
                  return Card(
                    clipBehavior: Clip.antiAlias,
                    child: InkWell(
                      onTap: () => _launch(app),
                      child: Padding(
                        padding: const EdgeInsets.all(16),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Icon(_iconFor(app), size: 30),
                            const Spacer(),
                            Text(
                              app.name,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(fontWeight: FontWeight.w800),
                            ),
                            if (app.genericName case final generic?)
                              Text(
                                generic,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: Theme.of(context).textTheme.bodySmall,
                              ),
                          ],
                        ),
                      ),
                    ),
                  );
                },
              ),
            );
          },
        ),
      ],
    );
  }
}
