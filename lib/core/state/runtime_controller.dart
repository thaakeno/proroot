import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/runtime_snapshot.dart';
import '../platform/runtime_bridge.dart';

class RuntimeController extends ChangeNotifier {
  RuntimeController(this.bridge);

  final RuntimeBridge bridge;
  StreamSubscription<RuntimeSnapshot>? _subscription;
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
      notifyListeners();
    });

    try {
      snapshot = await bridge.status();
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
    super.dispose();
  }
}
