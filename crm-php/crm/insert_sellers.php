<?php
// insert_sellers.php — inserts mechanic lottery serials into Mcode (10 points each)
header('Content-Type: application/json');
require_once 'db.php';

define('SCORE_PER_SERIAL', 10);

$data = json_decode(file_get_contents("php://input"), true);

if (!isset($data['description'], $data['serials']) || !is_array($data['serials'])) {
    echo json_encode(['success' => false, 'message' => 'ورودی نامعتبر است']);
    exit;
}

$description = trim($data['description']);
$serials = $data['serials'];

if ($description === '') {
    echo json_encode(['success' => false, 'message' => 'توضیحات الزامی است']);
    exit;
}

if (count($serials) === 0) {
    echo json_encode(['success' => false, 'message' => 'هیچ سریال معتبری یافت نشد']);
    exit;
}

$conn->begin_transaction();

try {
    $stmt = $conn->prepare("INSERT INTO Mcode (serial, phone, time, score, description) VALUES (?, '0', NOW(), ?, ?)");

    if (!$stmt) {
        throw new Exception("Prepare failed: " . $conn->error);
    }

    $score = SCORE_PER_SERIAL;

    foreach ($serials as $serial) {
        $stmt->bind_param("sis", $serial, $score, $description);
        if (!$stmt->execute()) {
            throw new Exception("Execute failed: " . $stmt->error);
        }
    }

    $conn->commit();
    echo json_encode(['success' => true, 'count' => count($serials), 'scorePerSerial' => SCORE_PER_SERIAL]);

} catch (Exception $e) {
    $conn->rollback();
    echo json_encode(['success' => false, 'message' => $e->getMessage()]);
}

if (isset($stmt)) {
    $stmt->close();
}

$conn->close();
?>
