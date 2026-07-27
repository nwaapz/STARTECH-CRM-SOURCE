<?php
include 'db.php';

if ($conn->connect_error) {
    die("Connection failed: " . $conn->connect_error);
}

// Get all messages from ad_sms
$sql = "SELECT message FROM ad_sms";
$result = $conn->query($sql);

$messages = [];
while ($row = $result->fetch_assoc()) {
    $messages[] = $row['message'];
}

// Limit the number of messages to 5
$messages = array_slice($messages, 0, 5);

$response = [
    'messages' => $messages,
    'count' => count($messages),
];

echo json_encode($response);

$conn->close();
?>
