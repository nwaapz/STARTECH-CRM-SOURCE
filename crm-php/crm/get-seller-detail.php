<?php
// get-seller-detail.php — serials and descriptions for one lottery participant
header('Content-Type: application/json');
require_once 'db.php';
require_once __DIR__ . '/image-verification-helper.php';

ensureImageVerificationSchema($conn);

$phone = trim($_GET['phone'] ?? '');
if ($phone === '') {
    echo json_encode(['success' => false, 'message' => 'شماره تلفن ارسال نشده است']);
    exit;
}

$sql = "SELECT serial, score, description, city FROM Mcode
        WHERE phone = ? AND score > 0
        AND NOT EXISTS (
            SELECT 1 FROM image_verifications iv
            WHERE iv.serial = Mcode.serial
            AND iv.status IN ('pending_capture', 'pending_review')
        )
        ORDER BY time DESC";
$stmt = $conn->prepare($sql);
$stmt->bind_param('s', $phone);
$stmt->execute();
$result = $stmt->get_result();

$serials = [];
$descriptions = [];
$city = '';
$totalScore = 0;

while ($row = $result->fetch_assoc()) {
    $serials[] = $row['serial'];
    $descriptions[] = $row['description'] ?? '';
    $totalScore += (int) $row['score'];
    if (!$city && !empty($row['city'])) {
        $city = $row['city'];
    }
}

$stmt->close();
$conn->close();

if (count($serials) === 0) {
    echo json_encode(['success' => false, 'message' => 'شرکت‌کننده یافت نشد']);
    exit;
}

echo json_encode([
    'success' => true,
    'phone' => $phone,
    'city' => $city,
    'score' => $totalScore,
    'serialCount' => count($serials),
    'serials' => $serials,
    'descriptions' => $descriptions
]);
?>
