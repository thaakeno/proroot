import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/linux_app.dart';
import '../models/runtime_snapshot.dart';
import '../platform/runtime_bridge.dart';

class RuntimeController extends ChangeNotifier {
  RuntimeController(this.bridge);

  final RuntimeBridge bridge;
  StreamSubscription<RuntimeSnapshot>? _subscription;
  Completer<void>? _runningCompleter;
  RuntimeSnapshot snapshot = const RuntimeSnapshot.initial();

  bool darkMode = true;
  int refreshRate = 120;
  double desktopScale = 1.0;
  String inputMode = 'trackpad';
  String performanceProfile = 'balanced';

  bool get busy => switch (snapshot.phase) {
        RuntimePhase.downloading ||
        RuntimePhase.extracting ||
        RuntimePhase.provisioning ||
        RuntimePhase.starting ||
        RuntimePhase.stopping => true,
        _ => false,
      };

  Future<void> initialize() async {
    final prefs = await SharedPreferences.getInstance();
    darkMode = prefs.getBool('darkMode') ?? true;
    refreshRate = prefs.getInt('refreshRate') ?? 120;
    desktopScale = prefs.getDouble('desktopScale') ?? 1.0;
    inputMode = prefs.getString('inputMode') ?? 'trackpad';
    performanceProfile = prefs.getString('performanceProfile') ?? 'balanced';

    _subscription = bridge.events.listen((next) {
      snapshot = next;
      if (next.running) {
        final pending = _runningCompleter;
        if (pending != null && !pending.isCompleted) pending.complete();
      } else if (next.phase == RuntimePhase.failed) {
        final pending = _runningCompleter;
        if (pending != null && !pending.isCompleted) {
          pending.completeError(StateError(next.detail ?? next.message));
        }
      }
      notifyListeners();
    });

    try {
      snapshot = await bridge.status();
      await _pushDisplayOptions();
      await bridge.setPerformanceProfile(performanceProfile);
    } catch (_) {
      snapshot = snapshot.copyWith(
        phase: RuntimePhase.failed,
        message: 'Android runtime bridge unavailable',
      );
    }
    notifyListeners();
  }

  Future<void> install() => bridge.install();
  Future<void> start() => bridge.start();
  Future<void> stop() => bridge.stop();

  Future<void> reset() async {
    await bridge.reset();
    snapshot = await bridge.status();
    notifyListeners();
  }

  Future<List<LinuxApp>> desktopApps() => bridge.desktopApps();

  Future<void> launchApp(String desktopId) async {
    if (!snapshot.installed) {
      throw StateError('Linux is not installed');
    }

    if (!snapshot.running) {
      _runningCompleter = Completer<void>();
      await bridge.start();
      await _runningCompleter!.future.timeout(const Duration(seconds: 35));
      _runningCompleter = null;
    }

    await bridge.launchDesktopApp(desktopId);
  }

  Future<void> showKeyboard() async {
    await bridge.showKeyboard();
  }

  Future<bool> setPointerCapture(bool enabled) => bridge.setPointerCapture(enabled);

  Future<void> setDarkMode(bool value) async {
    darkMode = value;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('darkMode', value);
  }

  Future<void> setRefreshRate(int value) async {
    refreshRate = value;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('refreshRate', value);
    await _pushDisplayOptions();
  }

  Future<void> setDesktopScale(double value) async {
    desktopScale = value;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setDouble('desktopScale', value);
    await _pushDisplayOptions();
  }

  Future<void> setInputMode(String value) async {
    inputMode = value;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('inputMode', value);
    await _pushDisplayOptions();
  }

  Future<void> setPerformanceProfile(String value) async {
    performanceProfile = value;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('performanceProfile', value);
    await bridge.setPerformanceProfile(value);
  }

  Future<void> _pushDisplayOptions() {
    return bridge.setDisplayOptions(
      refreshRate: refreshRate,
      scale: desktopScale,
      inputMode: inputMode,
    );
  }

  @override
  void dispose() {
    _subscription?.cancel();
    final pending = _runningCompleter;
    if (pending != null && !pending.isCompleted) {
      pending.completeError(StateError('Runtime controller disposed'));
    }
    super.dispose();
  }
}
