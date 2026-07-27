<?php
include 'db.php';
ini_set('display_errors', 1);
ini_set('display_startup_errors', 1);
error_reporting(E_ALL);
header('Content-Type: application/json');// Include the database connection

// Get JSON input
$data = json_decode(file_get_contents('php://input'), true);

if (!isset($data['serials']) || !is_array($data['serials'])) {
    echo json_encode(["success" => false, "message" => "Invalid input"]);
    exit;
}

$serials = $data['serials'];

// Prevent SQL Injection by using prepared statements
if (count($serials) > 0) {
    $placeholders = implode(',', array_fill(0, count($serials), '?'));
    $sql = "DELETE FROM $OldSerialTable WHERE $oldserialTable_serial IN ($placeholders)";
    
    $stmt = $conn->prepare($sql);
    
    if ($stmt) {
        $types = str_repeat('s', count($serials)); // Assuming serials are strings
        $stmt->bind_param($types, ...$serials);
        
        if ($stmt->execute()) {
            echo json_encode(["success" => true]);
        } else {
            echo json_encode(["success" => false, "message" => "Database error"]);
        }

        $stmt->close();
    } else {
        echo json_encode(["success" => false, "message" => "SQL preparation error"]);
    }
} else {
    echo json_encode(["success" => false, "message" => "No serials provided"]);
}

$conn->close();
?>
