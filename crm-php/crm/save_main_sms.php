<?php
// Include the database connection
include 'db.php';

// Get the JSON input data
$inputData = file_get_contents('php://input');
$data = json_decode($inputData, true);

// Check if data is valid
if (isset($data['a'], $data['b'], $data['c'])) {
    $messageA = $data['a'];
    $messageB = $data['b'];
    $messageC = $data['c'];

    // Assuming $conn is the mysqli connection from db.php
    try {
        // Prepare the SQL statement using mysqli
        $stmt = $conn->prepare("UPDATE main_sms SET a = ?, b = ?, c = ? WHERE id = 1");
        
        // Bind the parameters to the statement
        $stmt->bind_param("sss", $messageA, $messageB, $messageC); // 'sss' means string type for all 3 parameters

        // Execute the statement
        if ($stmt->execute()) {
            echo json_encode(['success' => true]);
        } else {
            echo json_encode(['success' => false, 'message' => 'Database update failed.']);
        }

        // Close the statement
        $stmt->close();
    } catch (Exception $e) {
        echo json_encode(['success' => false, 'message' => $e->getMessage()]);
    }
} else {
    echo json_encode(['success' => false, 'message' => 'Invalid input data.']);
}
?>
