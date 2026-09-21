import 'package:flutter/material.dart';
import '../../core/state/runtime_controller.dart';

class DesktopScreen extends StatefulWidget {
  const DesktopScreen({required this.controller, super.key});
  final RuntimeController controller;

  @override
  State<DesktopScreen> createState() => _DesktopScreenState();
}

class _DesktopScreenState extends State<DesktopScreen> {
  bool _controlsVisible = true;

  @override
  Widget build(BuildContext context) {
    final running = widget.controller.snapshot.running;
    return ColoredBox(
      color: Colors.black,
      child: Stack(
        children: [
          if (running)
            const Positioned.fill(
              child: AndroidView(
                viewType: 'dev.thaakeno.proroot/display',
                layoutDirection: TextDirection.ltr,
              ),
            )
          else
            Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 320),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.desktop_access_disabled_rounded, size: 56, color: Colors.white70),
                    const SizedBox(height: 16),
                    const Text('Linux is not running', style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.w700)),
                    const SizedBox(height: 8),
                    const Text('Start the machine and the native Wayland desktop will appear here.', textAlign: TextAlign.center, style: TextStyle(color: Colors.white60)),
                    const SizedBox(height: 22),
                    FilledButton.icon(onPressed: widget.controller.start, icon: const Icon(Icons.play_arrow_rounded), label: const Text('Start Linux')),
                  ],
                ),
              ),
            ),
          Positioned.fill(
            child: GestureDetector(
              behavior: HitTestBehavior.translucent,
              onTap: () => setState(() => _controlsVisible = !_controlsVisible),
              child: const SizedBox.expand(),
            ),
          ),
          AnimatedPositioned(
            duration: const Duration(milliseconds: 220),
            curve: Curves.easeOutCubic,
            top: _controlsVisible ? 12 : -72,
            left: 12,
            right: 12,
            child: IgnorePointer(
              ignoring: !_controlsVisible,
              child: SafeArea(
                child: Center(
                  child: Container(
                    constraints: const BoxConstraints(maxWidth: 560),
                    padding: const EdgeInsets.fromLTRB(10, 8, 8, 8),
                    decoration: BoxDecoration(
                      color: const Color(0xE614171D),
                      borderRadius: BorderRadius.circular(20),
                      border: Border.all(color: Colors.white12),
                    ),
                    child: Row(
                      children: [
                        const SizedBox(width: 8),
                        const Icon(Icons.circle, size: 10, color: Color(0xFF6EE7A6)),
                        const SizedBox(width: 8),
                        const Expanded(child: Text('Debian 13 · Plasma', style: TextStyle(color: Colors.white, fontWeight: FontWeight.w700))),
                        IconButton(onPressed: () => _launch('org.kde.konsole.desktop'), color: Colors.white, icon: const Icon(Icons.terminal_rounded)),
                        IconButton(onPressed: () => _launch('brave-browser.desktop'), color: Colors.white, icon: const Icon(Icons.language_rounded)),
                        IconButton(onPressed: () => _launch('firefox-esr.desktop'), color: Colors.white, icon: const Icon(Icons.public_rounded)),
                        IconButton(onPressed: widget.controller.stop, color: Colors.white, icon: const Icon(Icons.stop_rounded)),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _launch(String desktopId) async {
    await widget.controller.bridge.launchDesktopApp(desktopId);
  }
}
