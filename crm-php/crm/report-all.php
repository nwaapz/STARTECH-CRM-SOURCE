<?php
include 'db.php';
header("Content-Type: application/json; charset=UTF-8");


$conn->set_charset("utf8");

if ($conn->connect_error) {
    die(json_encode(["error" => "خطا در اتصال به دیتابیس: " . $conn->connect_error]));
}

// دریافت تعداد استعلام‌های دارای شماره تلفن از old_serial
$sql1 = "SELECT COUNT(*) AS count_old_serial FROM $OldSerialTable WHERE phone IS NOT NULL";
$result1 = $conn->query($sql1);
$count_old_serial = ($result1->num_rows > 0) ? $result1->fetch_assoc()["count_old_serial"] : 0;

// دریافت تعداد کل ردیف‌ها از جدول reject
$sql2 = "SELECT COUNT(*) AS count_reject FROM reject";
$result2 = $conn->query($sql2);
$count_reject = ($result2->num_rows > 0) ? $result2->fetch_assoc()["count_reject"] : 0;

// محاسبه تعداد کل استعلام‌ها
$total_requests = $count_old_serial + $count_reject;

echo json_encode([
    "total_requests" => $total_requests,
    "total_rejected" => $count_reject,
    "total_confirmed" => $count_old_serial
]);

$conn->close();
?>