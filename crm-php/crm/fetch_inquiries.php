<?php
header("Content-Type: application/json");
include 'db.php';

// Read JSON input
$input = file_get_contents('php://input');
$data  = json_decode($input, true);

$start_date = $data['start_date'] ?? null;
$end_date   = $data['end_date']   ?? null;

if (!$start_date || !$end_date) {
    echo json_encode(["error" => "تاریخ شروع و پایان الزامی هستند"]);
    exit;
}

// Select city along with the other fields
$sql = "SELECT phone, serial, km, city, time
        FROM old_serials
        WHERE time >= ? AND time <= ?
        ORDER BY time DESC";

$stmt = $conn->prepare($sql);
if (!$stmt) {
    // Log the real MySQL error for debugging
    error_log("MySQL prepare error: " . $conn->error);
    echo json_encode(["error" => "خطا در آماده‌سازی کوئری"]);
    exit;
}

// Bind exactly two parameters for the two placeholders
$stmt->bind_param("ss", $start_date, $end_date);

if (!$stmt->execute()) {
    error_log("MySQL execute error: " . $stmt->error);
    echo json_encode(["error" => "اجرای کوئری ناموفق بود"]);
    exit;
}

$result = $stmt->get_result();
$rows = [];

while ($row = $result->fetch_assoc()) {
    $rows[] = $row;
}

echo json_encode($rows);

$stmt->close();
$conn->close();
?>
