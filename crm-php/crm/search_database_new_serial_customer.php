<?php
header("Content-Type: application/json");
include 'db.php';

if ($conn->connect_error) {
    die(json_encode(["success" => false, "message" => "خطا در اتصال به دیتابیس"]));
}

$data = json_decode(file_get_contents("php://input"), true);
$searchType = $data["type"];
$searchValue = $conn->real_escape_string($data["value"]);

if ($searchType === "serial") {
    $query = "SELECT * FROM $OldSerialTable WHERE serial = '$searchValue'";
} else {
    $query = "SELECT * FROM $OldSerialTable WHERE phone = '$searchValue'";
}

$result = $conn->query($query);



if ($result->num_rows > 0) {
    $row = $result->fetch_assoc();
    $formatted_date = (!empty($row["time"]) && $row["time"] !== "0000-00-00 00:00:00") 
        ? date("Y-m-d", strtotime($row["time"])) 
        : "";
    echo json_encode([
        "success" => true, 
        "serial" => $row["serial"], 
        "phone" => $row["phone"], 
        "km" => $row["km"], 
        "time" => $formatted_date // تاریخ را هم ارسال کن
    ]);
} else {
    echo json_encode(["success" => false, "message" => "موردی یافت نشد"]);
}

$conn->close();
?>
