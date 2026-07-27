<?php
header('Content-Type: application/json; charset=utf-8');
require_once 'db.php';
require_once __DIR__ . '/test-data-reset.php';

requireTestDataAdminSession();

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['success' => false, 'message' => 'Method not allowed'], JSON_UNESCAPED_UNICODE);
    exit;
}

if (!ensureTestPhonesTable($conn)) {
    echo json_encode(['success' => false, 'message' => $conn->error], JSON_UNESCAPED_UNICODE);
    exit;
}

$result = $conn->query('SELECT phone FROM crm_test_phones ORDER BY created_at ASC');
if (!$result) {
    echo json_encode(['success' => false, 'message' => $conn->error], JSON_UNESCAPED_UNICODE);
    exit;
}

$phones = [];
while ($row = $result->fetch_assoc()) {
    $phones[] = $row['phone'];
}
$result->free();

if (count($phones) === 0) {
    echo json_encode(['success' => false, 'message' => 'هیچ شماره تستی ثبت نشده است'], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

$conn->begin_transaction();

try {
    $totals = [
        'old_serials' => 0,
        'new_serials' => 0,
        'Mcode' => 0,
        'winners' => 0,
        'lottery_suspended_phones' => 0,
        'seller' => 0,
    ];
    $details = [];

    foreach ($phones as $phone) {
        $outcome = clearRegistrationForPhone($conn, $phone);
        if (empty($outcome['success'])) {
            throw new Exception($outcome['message'] ?? 'خطا در پاکسازی');
        }
        $details[] = [
            'phone' => $outcome['phone'],
            'counts' => $outcome['counts'],
        ];
        foreach ($totals as $key => $value) {
            $totals[$key] += (int) ($outcome['counts'][$key] ?? 0);
        }
    }

    $conn->commit();

    echo json_encode([
        'success' => true,
        'phonesProcessed' => count($phones),
        'totals' => $totals,
        'details' => $details,
        'message' => 'داده‌های تست برای ' . count($phones) . ' شماره پاکسازی شد'
    ], JSON_UNESCAPED_UNICODE);
} catch (Exception $e) {
    $conn->rollback();
    echo json_encode(['success' => false, 'message' => $e->getMessage()], JSON_UNESCAPED_UNICODE);
}

$conn->close();
