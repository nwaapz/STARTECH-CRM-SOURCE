<?php
header('Content-Type: application/json; charset=utf-8');
require_once 'db.php';
require_once __DIR__ . '/image-verification-helper.php';

requireImageVerificationSession(3);

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['success' => false, 'message' => 'Method not allowed'], JSON_UNESCAPED_UNICODE);
    exit;
}

$data = json_decode(file_get_contents('php://input'), true);
$id = (int) ($data['id'] ?? 0);
$action = trim((string) ($data['action'] ?? ''));

$rejectActions = [
    'reject' => 'unreadable',
    'reject_unreadable' => 'unreadable',
    'reject_wrong_serial' => 'wrong_serial',
];

if ($id <= 0 || (!in_array($action, ['verify'], true) && !isset($rejectActions[$action]))) {
    echo json_encode(['success' => false, 'message' => 'درخواست نامعتبر'], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

$row = getVerificationById($conn, $id);
if (!$row) {
    echo json_encode(['success' => false, 'message' => 'رکورد یافت نشد'], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

if ($row['status'] !== 'pending_review') {
    echo json_encode(['success' => false, 'message' => 'این مورد در وضعیت بررسی نیست'], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

if ($action === 'verify') {
    $admin = $_SESSION['username'] ?? 'operator';
    if (!completeRegistrationAfterVerify($conn, $row, $admin)) {
        echo json_encode(['success' => false, 'message' => 'خطا در تأیید'], JSON_UNESCAPED_UNICODE);
        $conn->close();
        exit;
    }
    $activityStore = dirname(__DIR__) . '/api/activity_store.php';
    if (is_file($activityStore)) {
        require_once $activityStore;
        crm_activity_record($conn, 'photo_approved', [
            'serial' => (string) ($row['serial'] ?? ''),
            'phone' => (string) ($row['phone'] ?? ''),
            'message' => 'تأیید عکس سریال ' . (string) ($row['serial'] ?? ''),
            'source' => 'legacy_image_verification',
        ], null, (string) $admin);
    }
    echo json_encode(['success' => true, 'message' => 'ثبت‌نام تأیید و پیامک ارسال شد'], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

$rejectReason = $rejectActions[$action];
$rotated = rotateTokenForResubmit($conn, $row, $rejectReason);
if (!$rotated) {
    echo json_encode(['success' => false, 'message' => 'خطا در رد تصویر'], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

sendRejectSms($conn, $row['phone'], $row['serial'], $rotated['url'], $rejectReason);

$updated = getVerificationById($conn, $id);
$messages = [
    'unreadable' => 'اخطار تصویر ناخوانا برای کاربر ارسال شد',
    'wrong_serial' => 'اخطار سریال نامرتبط برای کاربر ارسال شد',
];
$activityStore = dirname(__DIR__) . '/api/activity_store.php';
if (is_file($activityStore)) {
    require_once $activityStore;
    $reasonLabel = $rejectReason === 'wrong_serial' ? 'سریال نامرتبط' : 'تصویر ناخوانا';
    crm_activity_record($conn, 'photo_rejected', [
        'serial' => (string) ($row['serial'] ?? ''),
        'phone' => (string) ($row['phone'] ?? ''),
        'reason' => $rejectReason,
        'message' => 'رد عکس سریال ' . (string) ($row['serial'] ?? '') . ' (' . $reasonLabel . ')',
        'source' => 'legacy_image_verification',
    ], null, (string) ($_SESSION['username'] ?? 'operator'));
}
echo json_encode([
    'success' => true,
    'message' => $messages[$rejectReason] ?? 'اخطار برای کاربر ارسال شد',
    'last_reject_at' => $updated['last_reject_at'] ?? null,
    'last_reject_reason' => $updated['last_reject_reason'] ?? $rejectReason,
    'reject_count' => (int) ($updated['reject_count'] ?? 0),
], JSON_UNESCAPED_UNICODE);

$conn->close();
