<?php
header('Content-Type: application/json; charset=utf-8');
require_once 'db.php';
require_once __DIR__ . '/image-verification-helper.php';

requireImageVerificationSession(3);

if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    $settings = getImageVerificationSettings($conn);
    echo json_encode(['success' => true, 'settings' => $settings], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['success' => false, 'message' => 'Method not allowed'], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

$data = json_decode(file_get_contents('php://input'), true);
if (!is_array($data)) {
    echo json_encode(['success' => false, 'message' => 'داده نامعتبر'], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

$payload = [
    'enable_s' => !empty($data['enable_s']) ? 1 : 0,
    'enable_m' => !empty($data['enable_m']) ? 1 : 0,
    'testers_only' => !empty($data['testers_only']) ? 1 : 0,
    'auto_verify_threshold' => (int) ($data['auto_verify_threshold'] ?? 85),
    'sms_link_request' => trim((string) ($data['sms_link_request'] ?? '')),
    'sms_verified' => trim((string) ($data['sms_verified'] ?? '')),
    'sms_rejected' => trim((string) ($data['sms_rejected'] ?? '')),
    'sms_rejected_unreadable' => trim((string) ($data['sms_rejected_unreadable'] ?? '')),
    'sms_rejected_wrong_serial' => trim((string) ($data['sms_rejected_wrong_serial'] ?? '')),
    'capture_base_url' => trim((string) ($data['capture_base_url'] ?? IV_CAPTURE_BASE_DEFAULT)),
];

if ($payload['sms_link_request'] === '' || $payload['sms_verified'] === '') {
    echo json_encode(['success' => false, 'message' => 'متن پیامک‌های اصلی نمی‌تواند خالی باشد'], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

if ($payload['sms_rejected_unreadable'] === '') {
    $payload['sms_rejected_unreadable'] = $payload['sms_rejected'] ?: IV_SMS_REJECTED_UNREADABLE_DEFAULT;
}
if ($payload['sms_rejected_wrong_serial'] === '') {
    $payload['sms_rejected_wrong_serial'] = $payload['sms_rejected'] ?: IV_SMS_REJECTED_WRONG_SERIAL_DEFAULT;
}
if ($payload['sms_rejected'] === '') {
    $payload['sms_rejected'] = IV_SMS_REJECTED_DEFAULT;
}

if (ivAreSerialTogglesLocked()) {
    $payload['enable_s'] = 0;
    $payload['enable_m'] = 0;
}

if (!saveImageVerificationSettings($conn, $payload)) {
    echo json_encode(['success' => false, 'message' => 'خطا در ذخیره تنظیمات'], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

echo json_encode(['success' => true, 'settings' => getImageVerificationSettings($conn)], JSON_UNESCAPED_UNICODE);
$conn->close();
