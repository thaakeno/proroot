enum RuntimePhase {
  missing,
  downloading,
  extracting,
  provisioning,
  ready,
  starting,
  running,
  stopping,
  failed,
}

class RuntimeSnapshot {
  const RuntimeSnapshot({
    required this.phase,
    required this.progress,
    required this.message,
    required this.downloadedBytes,
    required this.totalBytes,
    required this.speedBytesPerSecond,
    required this.elapsedSeconds,
    required this.etaSeconds,
    required this.installed,
    required this.running,
    this.detail,
  });

  const RuntimeSnapshot.initial()
      : phase = RuntimePhase.missing,
        progress = 0,
        message = 'Checking Linux environment',
        downloadedBytes = 0,
        totalBytes = 0,
        speedBytesPerSecond = 0,
        elapsedSeconds = 0,
        etaSeconds = null,
        installed = false,
        running = false,
        detail = null;

  final RuntimePhase phase;
  final double progress;
  final String message;
  final int downloadedBytes;
  final int totalBytes;
  final int speedBytesPerSecond;
  final int elapsedSeconds;
  final int? etaSeconds;
  final bool installed;
  final bool running;
  final String? detail;

  factory RuntimeSnapshot.fromMap(Map<dynamic, dynamic> map) {
    RuntimePhase parsePhase(String raw) {
      return RuntimePhase.values.firstWhere(
        (value) => value.name == raw,
        orElse: () => RuntimePhase.failed,
      );
    }

    return RuntimeSnapshot(
      phase: parsePhase((map['phase'] as String?) ?? 'failed'),
      progress: ((map['progress'] as num?) ?? 0).clamp(0, 1).toDouble(),
      message: (map['message'] as String?) ?? '',
      downloadedBytes: (map['downloadedBytes'] as num?)?.toInt() ?? 0,
      totalBytes: (map['totalBytes'] as num?)?.toInt() ?? 0,
      speedBytesPerSecond: (map['speedBytesPerSecond'] as num?)?.toInt() ?? 0,
      elapsedSeconds: (map['elapsedSeconds'] as num?)?.toInt() ?? 0,
      etaSeconds: (map['etaSeconds'] as num?)?.toInt(),
      installed: map['installed'] == true,
      running: map['running'] == true,
      detail: map['detail'] as String?,
    );
  }

  RuntimeSnapshot copyWith({
    RuntimePhase? phase,
    double? progress,
    String? message,
    int? downloadedBytes,
    int? totalBytes,
    int? speedBytesPerSecond,
    int? elapsedSeconds,
    int? etaSeconds,
    bool clearEta = false,
    bool? installed,
    bool? running,
    String? detail,
  }) {
    return RuntimeSnapshot(
      phase: phase ?? this.phase,
      progress: progress ?? this.progress,
      message: message ?? this.message,
      downloadedBytes: downloadedBytes ?? this.downloadedBytes,
      totalBytes: totalBytes ?? this.totalBytes,
      speedBytesPerSecond: speedBytesPerSecond ?? this.speedBytesPerSecond,
      elapsedSeconds: elapsedSeconds ?? this.elapsedSeconds,
      etaSeconds: clearEta ? null : etaSeconds ?? this.etaSeconds,
      installed: installed ?? this.installed,
      running: running ?? this.running,
      detail: detail ?? this.detail,
    );
  }
}
