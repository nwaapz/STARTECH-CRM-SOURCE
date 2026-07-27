<?php
header('Content-Type: application/json; charset=utf-8');
require_once 'db.php';
require_once __DIR__ . '/test-data-reset.php';

$data = json_decode(file_get_contents('php://input'), true);
$phone = normalizeIranPhone($data['phone'] ?? '');

if ($phone === '') {
    echo json_encode(['success' => false, 'message' => 'شماره تلفن ارسال نشده است']);
    exit;
}

$conn->begin_transaction();

try {
    $outcome = clearRegistrationForPhone($conn, $phone);
    if (empty($outcome['success'])) {
        throw new Exception($outcome['message'] ?? 'خطا در بازنشانی');
    }

    $conn->commit();

    $counts = $outcome['counts'];
    echo json_encode([
        'success' => true,
        'phone' => $outcome['phone'],
        'rowsReset' => $counts['Mcode'],
        'oldSerialsReset' => $counts['old_serials'],
        'newSerialsReset' => $counts['new_serials'],
        'winnersRemoved' => $counts['winners'],
        'suspendedRemoved' => $counts['lottery_suspended_phones'],
        'sellerReset' => $counts['seller'],
        'message' => 'اطلاعات مشتری بازنشانی شد و سریال‌ها آزاد شدند.'
    ]);
} catch (Exception $e) {
    $conn->rollback();
    echo json_encode(['success' => false, 'message' => $e->getMessage()]);
}

$conn->close();
?>
