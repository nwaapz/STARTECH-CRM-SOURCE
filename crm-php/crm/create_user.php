<?php
header('Content-Type: application/json');

include 'db.php';

// Check connection
if ($conn->connect_error) {
    echo json_encode(['success' => false, 'message' => 'Database connection failed: ' . $conn->connect_error]);
    exit;
}

// Get JSON input from the request
$data = json_decode(file_get_contents('php://input'), true);

// Validate input data
if (!isset($data['username'], $data['password'], $data['role'])) {
    echo json_encode(['success' => false, 'message' => 'Invalid input']);
    exit;
}

$username = trim($data['username']);
$password = trim($data['password']);
$role     = intval($data['role']);

// Hash the password before storing it (using PHP's password_hash)
$passwordHash = password_hash($password, PASSWORD_DEFAULT);

// Prepare and execute the INSERT query using a prepared statement
$sql = "INSERT INTO users (name, pass, role) VALUES (?, ?, ?)";
$stmt = $conn->prepare($sql);
if (!$stmt) {
    echo json_encode(['success' => false, 'message' => 'Failed to prepare statement: ' . $conn->error]);
    exit;
}

$stmt->bind_param('ssi', $username, $passwordHash, $role);

if ($stmt->execute()) {
    echo json_encode(['success' => true]);
} else {
    echo json_encode(['success' => false, 'message' => 'Error creating user: ' . $stmt->error]);
}

$stmt->close();
$conn->close();
?>
