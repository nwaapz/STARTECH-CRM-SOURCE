<?php
header('Content-Type: application/json; charset=utf-8');
require_once 'db.php';
require_once __DIR__ . '/lottery-sms-helper.php';
require_once __DIR__ . '/test-data-reset.php';

requireSmsSettingsSession(5);

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['success' => false, 'message' => 'Method not allowed'], JSON_UNESCAPED_UNICODE);
    exit;
}

if (!ensureTestPhonesTable($conn)) {
    echo json_encode(['success' => false, 'message' => $conn->error], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

$result = $conn->query('SELECT phone FROM crm_test_phones ORDER BY created_at ASC');
if (!$result) {
    echo json_encode(['success' => false, 'message' => $conn->error], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

$phones = [];
while ($row = $result->fetch_assoc()) {
    $phones[] = $row['phone'];
}
$result->free();

if (count($phones) === 0) {
    echo json_encode([
        'success' => false,
        'message' => 'هیچ شماره تستی ثبت نشده است. ابتدا در بخش مدیریت شماره‌های تست را اضافه کنید.'
    ], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

$smsMessage = getWinnerSmsMessage($conn);
$sent = 0;
$failed = 0;
$errors = [];

foreach ($phones as $phone) {
    try {
        sendWinnerSms($phone, $smsMessage);
        $sent++;
    } catch (Throwable $e) {
        $failed++;
        $errors[] = ['phone' => $phone, 'error' => $e->getMessage()];
    }
}

echo json_encode([
    'success' => $failed === 0,
    'sent' => $sent,
    'failed' => $failed,
    'phones' => $phones,
    'errors' => $errors,
    'message' => $failed === 0
        ? "پیامک آزمایشی به {$sent} شماره ارسال شد."
        : "ارسال به {$sent} شماره موفق و {$failed} شماره ناموفق بود."
], JSON_UNESCAPED_UNICODE);

$conn->close();
