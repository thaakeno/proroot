class LinuxApp {
  const LinuxApp({
    required this.id,
    required this.name,
    required this.categories,
    this.genericName,
    this.icon,
  });

  final String id;
  final String name;
  final String? genericName;
  final String? icon;
  final List<String> categories;

  factory LinuxApp.fromMap(Map<dynamic, dynamic> map) {
    final rawCategories = map['categories'];
    return LinuxApp(
      id: (map['id'] as String?) ?? '',
      name: (map['name'] as String?) ?? '',
      genericName: map['genericName'] as String?,
      icon: map['icon'] as String?,
      categories: rawCategories is List
          ? rawCategories.map((value) => '$value').toList(growable: false)
          : const [],
    );
  }
}
