<?php
header('Content-Type: application/json');
error_reporting(E_ALL);
ini_set('display_errors', 1);

include "db.php"; // Ensure this file is correct

// Check if the database connection exists
if (!$conn) {
    echo json_encode(["error" => "Database connection failed: " . mysqli_connect_error()]);
    exit;
}

$sql = "SELECT id, name, role FROM users"; // Ensure role is selected
$result = mysqli_query($conn, $sql);

$users = [];
while ($row = mysqli_fetch_assoc($result)) {
    // Convert role to an integer to avoid type issues
    $row['role'] = (int) $row['role']; 
    $users[] = $row;
}

echo json_encode($users);
?>
