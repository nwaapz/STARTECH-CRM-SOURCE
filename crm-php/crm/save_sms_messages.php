<?php
// اطلاعات دیتابیس
include 'db.php';
ini_set('display_errors', 1);
ini_set('display_startup_errors', 1);
error_reporting(E_ALL);

// اتصال به دیتابیس


if ($conn->connect_error) {
    die(json_encode(['success' => false, 'message' => 'Connection failed: ' . $conn->connect_error]));
}

// دریافت داده‌های ارسال‌شده
$data = json_decode(file_get_contents('php://input'), true);

// بررسی اگر پیام‌ها خالی هستند و درخواست حذف پیام‌ها است
if (empty($data['messages'])) {
    $sql = "DELETE FROM ad_sms"; // حذف تمامی پیام‌ها
    if ($conn->query($sql) === TRUE) {
        echo json_encode(["success" => true, "message" => "همه پیام‌ها تبلیغاتی از دیتابیس حذف شدند."]);
    } else {
        echo json_encode(["success" => false, "message" => "خطا در حذف پیام‌ها: " . $conn->error]);
    }
    exit; // اتمام اسکریپت بعد از حذف
}

$messages = $data['messages'];

// حذف پیام‌های قدیمی
if ($conn->query("DELETE FROM ad_sms") === FALSE) {
    echo json_encode(['success' => false, 'message' => 'Error deleting old messages']);
    exit;
}

// آماده‌سازی پرس‌وجو برای وارد کردن داده‌ها
$stmt = $conn->prepare("INSERT INTO ad_sms (message) VALUES (?)");
$stmt->bind_param("s", $message);

// وارد کردن پیام‌ها به دیتابیس
foreach ($messages as $message) {
    if (!$stmt->execute()) {
        echo json_encode(['success' => false, 'message' => 'Error inserting message', 'error' => $stmt->error]);
        exit;
    }
}

$stmt->close();
$conn->close();

echo json_encode(['success' => true]);
?>
