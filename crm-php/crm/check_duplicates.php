<?php
include 'db.php';
error_reporting(E_ALL);
ini_set('display_errors', 1);
ini_set('display_startup_errors', 1);


header("Content-Type: application/json");

$rawData = file_get_contents("php://input");
error_log("📥 ورودی خام از کلاینت: " . $rawData);

$data = json_decode($rawData, true);

if (!$data || !isset($data["serials"]) || !is_array($data["serials"])) {
    echo json_encode(["error" => "❌ Invalid input", "received_data" => $rawData]);
    exit;
}

// دریافت داده‌های ارسالی
$data = json_decode(file_get_contents("php://input"), true);
if (!isset($data["serials"]) || !is_array($data["serials"])) {
    echo json_encode(["error" => "Invalid input"]);
    exit;
}

$serials = $data["serials"];
$table = $data['table'] ?? 'old_serials';

// جلوگیری از خطا در صورت ارسال لیست خالی
if (empty($serials)) {
    echo json_encode([]);
    exit;
}

$allowedTables = ['new_serials', 'old_serials'];
if (!in_array($table, $allowedTables)) {
    echo json_encode(["error" => "Invalid table selection"]);
    exit;
}

$placeholders = implode(',', array_fill(0, count($serials), '?'));
$sql = "SELECT serial FROM $table WHERE serial IN ($placeholders)";

$stmt = $conn->prepare($sql);

if ($stmt === false) {
    echo json_encode(["error" => "SQL Prepare failed: " . $conn->error]);
    exit;
}

// تنظیم نوع متغیرهای ارسالی به SQL
$types = str_repeat('s', count($serials));
$stmt->bind_param($types, ...$serials);

$stmt->execute();
$result = $stmt->get_result();

$duplicates = [];
while ($row = $result->fetch_assoc()) {
    $duplicates[] = $row["serial"];
}

$stmt->close();
$conn->close();

echo json_encode($duplicates);
?>
