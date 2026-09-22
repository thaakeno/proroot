class BuildInfo {
  const BuildInfo._();

  static const version = String.fromEnvironment(
    'APP_VERSION',
    defaultValue: '0.2.0-dev.1',
  );
  static const build = String.fromEnvironment(
    'APP_BUILD',
    defaultValue: 'local',
  );
  static const gitSha = String.fromEnvironment(
    'GIT_SHA',
    defaultValue: 'local',
  );
  static const prorootVersion = String.fromEnvironment(
    'PROROOT_VERSION',
    defaultValue: 'unknown',
  );

  static String get shortGitSha =>
      gitSha.length > 12 ? gitSha.substring(0, 12) : gitSha;
}
