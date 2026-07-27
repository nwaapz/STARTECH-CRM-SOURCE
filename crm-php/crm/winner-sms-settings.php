<?php
header('Content-Type: application/json; charset=utf-8');
require_once 'db.php';
require_once __DIR__ . '/lottery-sms-helper.php';

requireSmsSettingsSession(3);

if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    $message = getWinnerSmsMessage($conn);
    echo json_encode(['success' => true, 'message' => $message], JSON_UNESCAPED_UNICODE);
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
$message = trim((string) ($data['message'] ?? ''));

if ($message === '') {
    echo json_encode(['success' => false, 'message' => 'متن پیامک نمی‌تواند خالی باشد'], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

if (!saveWinnerSmsMessage($conn, $message)) {
    echo json_encode(['success' => false, 'message' => 'خطا در ذخیره پیامک برنده'], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

echo json_encode(['success' => true, 'message' => $message], JSON_UNESCAPED_UNICODE);
$conn->close();
