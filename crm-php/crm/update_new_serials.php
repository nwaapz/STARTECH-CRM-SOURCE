<?php
header("Content-Type: application/json");
include 'db.php';



if ($conn->connect_error) {
    die(json_encode(["success" => false, "message" => "Database connection failed"]));
}

$data = json_decode(file_get_contents("php://input"), true);

if (!isset($data["serial"]) || !isset($data["phone"])) {
    echo json_encode(["success" => false, "message" => "Serial and phone are required"]);
    exit;
}

$serial = $conn->real_escape_string($data["serial"]);
$phone = $conn->real_escape_string($data["phone"]);
$km = isset($data["km"]) ? $conn->real_escape_string($data["km"]) : "";
$date = isset($data["date"]) ? $conn->real_escape_string($data["date"]) : "";

$sql = "UPDATE $OldSerialTable SET phone = '$phone', km = '$km', time = '$date' WHERE serial = '$serial'";

if ($conn->query($sql) === TRUE) {
    echo json_encode(["success" => true]);
} else {
    echo json_encode(["success" => false, "message" => "Update failed: " . $conn->error]);
}

$conn->close();
?>
