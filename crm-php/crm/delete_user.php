<?php
header('Content-Type: application/json');

include 'db.php';

// Check connection
if ($conn->connect_error) {
    echo json_encode(['success' => false, 'message' => 'Database connection failed']);
    exit;
}

// Get JSON input
$data = json_decode(file_get_contents('php://input'), true);
if (!isset($data['userId'])) {
    echo json_encode(['success' => false, 'message' => 'Invalid input']);
    exit;
}

$userId = intval($data['userId']);

// Prepare the DELETE statement
$sql = "DELETE FROM users WHERE id = ?";
$stmt = $conn->prepare($sql);
if (!$stmt) {
    echo json_encode(['success' => false, 'message' => 'Failed to prepare statement']);
    exit;
}

$stmt->bind_param('i', $userId);

// Execute and check for success
if ($stmt->execute()) {
    echo json_encode(['success' => true]);
} else {
    echo json_encode(['success' => false, 'message' => 'Error deleting user']);
}

$stmt->close();
$conn->close();
?>
