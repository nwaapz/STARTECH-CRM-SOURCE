<?php
// get_new_sms_content.php

// Database connection (ensure you replace with your own connection details)
include 'db.php';

// Set the PDO error mode to exception
//$conn->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);

if ($conn->connect_error) {
    die(json_encode(['success' => false, 'message' => "Database connection failed: " . $conn->connect_error]));
}

// Prepare query
$sql = "SELECT message FROM new_sms LIMIT 1";
$result = $conn->query($sql);

// Fetch data
if ($result && $row = $result->fetch_assoc()) {
    echo json_encode(['success' => true, 'message' => $row['message']]);
} else {
    echo json_encode(['success' => false, 'message' => 'No message found']);
}

// Close connection
$conn->close();
?>
