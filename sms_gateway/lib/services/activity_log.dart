import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/log_entry.dart';

class ActivityLog {
  ActivityLog._();

  static final ActivityLog instance = ActivityLog._();
  static const _storageKey = 'activity_log';
  static const maxEntries = 50;

  final ValueNotifier<List<LogEntry>> entries = ValueNotifier<List<LogEntry>>([]);

  Future<void> load() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getStringList(_storageKey) ?? [];
    entries.value = raw
        .map((item) => LogEntry.fromJson(jsonDecode(item) as Map<String, dynamic>))
        .toList();
  }

  Future<void> add(LogEntry entry) async {
    final updated = [entry, ...entries.value].take(maxEntries).toList();
    entries.value = updated;

    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList(
      _storageKey,
      updated.map((e) => jsonEncode(e.toJson())).toList(),
    );
  }

  Future<void> clear() async {
    entries.value = [];
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_storageKey);
  }
}
