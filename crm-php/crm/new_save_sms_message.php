<?php

include 'db.php';
// Database connection (ensure you replace with your own connection details)

// Check connection
if ($conn->connect_error) {
    die(json_encode(['success' => false, 'message' => "Database connection failed: " . $conn->connect_error]));
}

// Get the raw POST data
$data = json_decode(file_get_contents('php://input'), true);

// Check if 'message' is provided in the request
if (isset($data['message'])) {
    $message = $data['message'];

    // Delete old messages
    if ($conn->query("DELETE FROM new_sms") === FALSE) {
        echo json_encode(['success' => false, 'message' => 'Error deleting old messages']);
        exit;
    }

    // Prepare and bind the SQL statement
    $stmt = $conn->prepare("INSERT INTO new_sms (message) VALUES (?)");
    if ($stmt === false) {
        echo json_encode(['success' => false, 'message' => 'Error preparing statement']);
        exit;
    }

    $stmt->bind_param("s", $message);

    // Execute the query
    if ($stmt->execute()) {
        echo json_encode(['success' => true]);
    } else {
        echo json_encode(['success' => false, 'message' => 'Error inserting message']);
    }

    // Close statement
    $stmt->close();
} else {
    // Return an error if no message is provided
    echo json_encode(['success' => false, 'message' => 'No message provided']);
}

// Close connection
$conn->close();
?>
