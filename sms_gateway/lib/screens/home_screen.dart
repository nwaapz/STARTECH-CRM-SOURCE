import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:permission_handler/permission_handler.dart';

import '../models/log_entry.dart';
import '../services/activity_log.dart';
import '../services/background_service.dart';
import '../services/settings_store.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _settingsStore = SettingsStore();
  final _urlController = TextEditingController();
  final _dateFormat = DateFormat('HH:mm:ss');

  bool _serviceRunning = false;
  bool _loading = true;
  Map<Permission, PermissionStatus> _permissions = {};

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  Future<void> _initialize() async {
    await ActivityLog.instance.load();
    final url = await _settingsStore.getGatewayUrl();
    _urlController.text = url;
    await _refreshState();
    if (mounted) {
      setState(() => _loading = false);
    }
  }

  Future<void> _refreshState() async {
    final permissions = await Future.wait([
      Permission.sms.status,
      Permission.notification.status,
    ]);

    final running = await isGatewayServiceRunning();

    if (mounted) {
      setState(() {
        _permissions = {
          Permission.sms: permissions[0],
          Permission.notification: permissions[1],
        };
        _serviceRunning = running;
      });
    }
  }

  Future<void> _requestPermissions() async {
    await [
      Permission.sms,
      Permission.notification,
    ].request();
    await _refreshState();
  }

  Future<void> _saveGatewayUrl() async {
    await _settingsStore.setGatewayUrl(_urlController.text);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Gateway URL saved')),
      );
    }
  }

  Future<void> _toggleService() async {
    if (_serviceRunning) {
      await stopGatewayService();
    } else {
      final smsStatus = await Permission.sms.status;
      if (!smsStatus.isGranted) {
        await _requestPermissions();
        if (!(await Permission.sms.status).isGranted) {
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('SMS permissions are required')),
            );
          }
          return;
        }
      }

      await startGatewayService();
    }

    await Future<void>.delayed(const Duration(milliseconds: 500));
    await _refreshState();
  }

  Future<void> _openBatterySettings() async {
    await openAppSettings();
  }

  @override
  void dispose() {
    _urlController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('SMS Gateway'),
        actions: [
          IconButton(
            onPressed: _refreshState,
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh',
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _refreshState,
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  _buildStatusCard(),
                  const SizedBox(height: 16),
                  _buildGatewayUrlCard(),
                  const SizedBox(height: 16),
                  _buildPermissionsCard(),
                  const SizedBox(height: 16),
                  _buildActionsCard(),
                  const SizedBox(height: 16),
                  _buildLogCard(),
                ],
              ),
            ),
    );
  }

  Widget _buildStatusCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(
              _serviceRunning ? Icons.cell_tower : Icons.cell_tower_outlined,
              color: _serviceRunning ? Colors.green : Colors.grey,
              size: 32,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _serviceRunning ? 'Gateway running' : 'Gateway stopped',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  Text(
                    _serviceRunning
                        ? 'Incoming SMS are forwarded to the server.'
                        : 'Tap Start to begin listening.',
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildGatewayUrlCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Gateway URL', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            TextField(
              controller: _urlController,
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                hintText: SettingsStore.defaultGatewayUrl,
              ),
              keyboardType: TextInputType.url,
            ),
            const SizedBox(height: 8),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton(
                onPressed: _saveGatewayUrl,
                child: const Text('Save URL'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPermissionsCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Permissions', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _permissionChip('SMS', _permissions[Permission.sms]),
                _permissionChip(
                  'Notifications',
                  _permissions[Permission.notification],
                ),
              ],
            ),
            const SizedBox(height: 12),
            OutlinedButton(
              onPressed: _requestPermissions,
              child: const Text('Request permissions'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildActionsCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            FilledButton.icon(
              onPressed: _toggleService,
              icon: Icon(_serviceRunning ? Icons.stop : Icons.play_arrow),
              label: Text(_serviceRunning ? 'Stop gateway' : 'Start gateway'),
            ),
            const SizedBox(height: 8),
            OutlinedButton.icon(
              onPressed: _openBatterySettings,
              icon: const Icon(Icons.battery_charging_full),
              label: const Text('Battery optimization settings'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildLogCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    'Activity log',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                TextButton(
                  onPressed: () async {
                    await ActivityLog.instance.clear();
                  },
                  child: const Text('Clear'),
                ),
              ],
            ),
            const SizedBox(height: 8),
            ValueListenableBuilder<List<LogEntry>>(
              valueListenable: ActivityLog.instance.entries,
              builder: (context, entries, _) {
                if (entries.isEmpty) {
                  return const Text('No messages processed yet.');
                }

                return Column(
                  children: entries
                      .map((entry) => _buildLogTile(entry))
                      .toList(),
                );
              },
            ),
          ],
        ),
      ),
    );
  }

  Widget _permissionChip(String label, PermissionStatus? status) {
    final granted = status?.isGranted ?? false;
    return Chip(
      avatar: Icon(
        granted ? Icons.check_circle : Icons.error_outline,
        size: 18,
        color: granted ? Colors.green : Colors.orange,
      ),
      label: Text(label),
    );
  }

  Widget _buildLogTile(LogEntry entry) {
    final statusColor = switch (entry.status) {
      'sent' => Colors.green,
      'gateway_error' => Colors.orange,
      _ => Colors.red,
    };

    return ListTile(
      contentPadding: EdgeInsets.zero,
      title: Text('${entry.sender} • ${entry.status}'),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(_dateFormat.format(entry.timestamp)),
          Text('In: ${entry.inbound}'),
          if (entry.reply != null) Text('Out: ${entry.reply}'),
          if (entry.error != null) Text('Error: ${entry.error}'),
        ],
      ),
      leading: Icon(Icons.sms, color: statusColor),
    );
  }
}
