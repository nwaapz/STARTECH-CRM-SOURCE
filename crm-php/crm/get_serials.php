<?php
include 'db.php';
if ($conn->connect_error) {
    die("Connection failed: " . $conn->connect_error);
}

// Fetch serial, phone, km, and time from the database
$sql = "SELECT serial, phone, km, time FROM $OldSerialTable";  // Update with actual table and columns
$result = $conn->query($sql);

$serials = [];
if ($result->num_rows > 0) {
    while ($row = $result->fetch_assoc()) {
        $serials[] = $row; // Store all data in an associative array
    }
}

// Return data as JSON
echo json_encode($serials);
$conn->close();
?>
