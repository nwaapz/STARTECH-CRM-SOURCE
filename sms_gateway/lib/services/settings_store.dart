import 'package:shared_preferences/shared_preferences.dart';

class SettingsStore {
  static const defaultGatewayUrl =
      'https://startechgroup.ir/gateway/Gateway.php';
  static const _gatewayUrlKey = 'gateway_url';
  static const _autoStartKey = 'auto_start';

  Future<String> getGatewayUrl() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_gatewayUrlKey) ?? defaultGatewayUrl;
  }

  Future<void> setGatewayUrl(String url) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_gatewayUrlKey, url.trim());
  }

  Future<bool> getAutoStart() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getBool(_autoStartKey) ?? true;
  }

  Future<void> setAutoStart(bool value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_autoStartKey, value);
  }
}
