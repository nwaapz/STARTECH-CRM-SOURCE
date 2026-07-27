<?php
header('Content-Type: application/json');
include 'db.php';
require_once __DIR__ . '/lottery-preset-store.php';
require_once __DIR__ . '/lottery-sms-helper.php';

$data = json_decode(file_get_contents("php://input"), true);
$phone = $data['phone'] ?? '';

if ($phone) {
    $conn->begin_transaction();

    try {
        $stmt1 = $conn->prepare("UPDATE Mcode SET score = 0 WHERE phone = ?");
        if (!$stmt1) {
            throw new Exception($conn->error);
        }
        $stmt1->bind_param("s", $phone);
        if (!$stmt1->execute()) {
            throw new Exception($stmt1->error);
        }
        $stmt1->close();

        $winnerMessage = getWinnerSmsMessage($conn);
        sendWinnerSms($phone, $winnerMessage);

        $stmt2 = $conn->prepare("INSERT INTO winners (phone, time) VALUES (?, NOW())");
        if (!$stmt2) {
            throw new Exception($conn->error);
        }
        $stmt2->bind_param("s", $phone);
        if (!$stmt2->execute()) {
            throw new Exception($stmt2->error);
        }
        $stmt2->close();

        $conn->commit();

        lotteryPresetClear();

        echo json_encode([
            "success" => true,
            "phone" => $phone,
            "resetAt" => date('Y-m-d H:i:s')
        ]);
    } catch (Exception $e) {
        $conn->rollback();
        echo json_encode(["success" => false, "message" => $e->getMessage()]);
    }
} else {
    echo json_encode(["success" => false, "message" => "شماره تلفن ارسال نشده است"]);
}
?>
