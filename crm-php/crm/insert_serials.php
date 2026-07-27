<?php
// insert_serials.php
include 'db.php';
require_once __DIR__ . '/serial-utils.php';
require_once __DIR__ . '/lottery-sync.php';

$data = json_decode(file_get_contents("php://input"), true);
$serials = $data['serials'];
$table = $data['table'];

if (!empty($serials) && !empty($table)) {
    try {
        $allowedTables = ['old_serials', 'new_serials'];
        if (!in_array($table, $allowedTables)) {
            echo json_encode(["success" => false, "message" => "Invalid table name"]);
            exit;
        }

        $stmt = $conn->prepare("INSERT INTO `$table` (serial) VALUES (?)");

        if ($stmt === false) {
            echo json_encode(["success" => false, "message" => "Failed to prepare statement"]);
            exit;
        }

        $conn->begin_transaction();

        foreach ($serials as $serial) {
            $stmt->bind_param("s", $serial);
            if (!$stmt->execute()) {
                throw new Exception("Error executing query: " . $stmt->error);
            }

            if ($table === 'old_serials') {
                insertSToMPreRegisterMcode($conn, $serial);
            }
        }

        $conn->commit();
        echo json_encode(["success" => true]);

    } catch (Exception $e) {
        $conn->rollback();
        echo json_encode(["success" => false, "message" => $e->getMessage()]);
    }
} else {
    echo json_encode(["success" => false, "message" => "No serials or table provided"]);
}
?>
