<?php
header('Content-Type: application/json');

include 'db.php';

// Check the connection
if ($conn->connect_error) {
    echo json_encode([
        'success' => false, 
        'message' => 'Database connection failed: ' . $conn->connect_error
    ]);
    exit;
}

// Get JSON input from the request body
$data = json_decode(file_get_contents('php://input'), true);

// Validate input data
if (!isset($data['username'], $data['newPassword'])) {
    echo json_encode([
        'success' => false, 
        'message' => 'Invalid input'
    ]);
    exit;
}

$username    = trim($data['username']);
$newPassword = trim($data['newPassword']);

// Prepare an UPDATE statement to change the user's password without hashing it
$sql = "UPDATE users SET pass = ? WHERE name = ?";
$stmt = $conn->prepare($sql);

if (!$stmt) {
    echo json_encode([
        'success' => false, 
        'message' => 'Failed to prepare statement: ' . $conn->error
    ]);
    exit;
}

$stmt->bind_param('ss', $newPassword, $username);

if ($stmt->execute()) {
    echo json_encode(['success' => true]);
} else {
    echo json_encode([
        'success' => false, 
        'message' => 'Error updating password: ' . $stmt->error
    ]);
}

$stmt->close();
$conn->close();
?>
