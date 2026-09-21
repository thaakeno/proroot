import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

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
  final _searchController = TextEditingController();
  String _query = '';

  @override
  void initState() {
    super.initState();
    _apps = widget.controller.desktopApps();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  void _refresh() {
    setState(() => _apps = widget.controller.desktopApps());
  }

  List<LinuxApp> _filter(List<LinuxApp> apps) {
    final query = _query.trim().toLowerCase();
    if (query.isEmpty) return apps;
    return apps.where((app) {
      final haystack = <String>[
        app.name,
        app.genericName ?? '',
        app.categories.join(' '),
      ].join(' ').toLowerCase();
      return haystack.contains(query);
    }).toList(growable: false);
  }

  IconData _fallbackIcon(LinuxApp app) {
    final text = '${app.name} ${app.icon ?? ''} ${app.categories.join(' ')}'.toLowerCase();
    if (text.contains('browser') || text.contains('firefox') || text.contains('brave')) {
      return Icons.language_rounded;
    }
    if (text.contains('terminal') || text.contains('konsole')) {
      return Icons.terminal_rounded;
    }
    if (text.contains('code') || text.contains('development')) {
      return Icons.code_rounded;
    }
    if (text.contains('file') || text.contains('dolphin')) {
      return Icons.folder_rounded;
    }
    if (text.contains('office') || text.contains('writer')) {
      return Icons.description_rounded;
    }
    if (text.contains('video') || text.contains('vlc')) {
      return Icons.play_circle_outline_rounded;
    }
    if (text.contains('graphics') || text.contains('gimp')) {
      return Icons.brush_rounded;
    }
    if (text.contains('settings')) {
      return Icons.tune_rounded;
    }
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
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 16),
          sliver: SliverToBoxAdapter(
            child: SearchBar(
              controller: _searchController,
              leading: const Icon(Icons.search_rounded),
              hintText: 'Search Linux apps',
              onChanged: (value) => setState(() => _query = value),
              trailing: [
                if (_query.isNotEmpty)
                  IconButton(
                    tooltip: 'Clear search',
                    onPressed: () {
                      _searchController.clear();
                      setState(() => _query = '');
                    },
                    icon: const Icon(Icons.close_rounded),
                  ),
              ],
            ),
          ),
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

            if (snapshot.hasError) {
              return SliverFillRemaining(
                hasScrollBody: false,
                child: Center(
                  child: Padding(
                    padding: const EdgeInsets.all(32),
                    child: Text('Could not load applications: ${snapshot.error}'),
                  ),
                ),
              );
            }

            final allApps = snapshot.data ?? const <LinuxApp>[];
            final apps = _filter(allApps);
            if (allApps.isEmpty) {
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

            if (apps.isEmpty) {
              return const SliverFillRemaining(
                hasScrollBody: false,
                child: Center(child: Text('No apps match this search.')),
              );
            }

            return SliverPadding(
              padding: const EdgeInsets.fromLTRB(20, 4, 20, 120),
              sliver: SliverGrid.builder(
                itemCount: apps.length,
                gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                  maxCrossAxisExtent: 220,
                  mainAxisExtent: 148,
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
                            _LinuxAppIcon(app: app, fallback: _fallbackIcon(app)),
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

class _LinuxAppIcon extends StatelessWidget {
  const _LinuxAppIcon({required this.app, required this.fallback});

  final LinuxApp app;
  final IconData fallback;

  @override
  Widget build(BuildContext context) {
    final path = app.iconPath;
    final fallbackWidget = Icon(fallback, size: 30);

    return Container(
      width: 50,
      height: 50,
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(14),
      ),
      child: path == null ? fallbackWidget : _iconFile(path, fallbackWidget),
    );
  }

  Widget _iconFile(String path, Widget fallback) {
    final file = File(path);
    if (!file.existsSync()) return fallback;

    if (path.toLowerCase().endsWith('.svg')) {
      return SvgPicture.file(
        file,
        fit: BoxFit.contain,
        placeholderBuilder: (_) => fallback,
        errorBuilder: (_, __, ___) => fallback,
      );
    }

    return Image.file(
      file,
      fit: BoxFit.contain,
      filterQuality: FilterQuality.medium,
      errorBuilder: (_, __, ___) => fallback,
    );
  }
}
