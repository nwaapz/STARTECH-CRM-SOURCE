<?php
include 'db.php';

if ($conn->connect_error) {
    die("Connection failed: " . $conn->connect_error);
}

$user = $_POST['username'];
$pass = $_POST['password'];

// Debugging: Log the password to check
error_log("Debug: Password from input = " . $pass);

// Prepare and execute the query
$stmt = $conn->prepare("SELECT role, pass FROM users WHERE name = ?");

if (!$stmt) {
    die("SQL Prepare Error: " . $conn->error); // 💥 Show detailed MySQL error
}

$stmt->bind_param("s", $user);
$stmt->execute();
$result = $stmt->get_result();

if ($result->num_rows > 0) {
    // Fetch the user's data
    $row = $result->fetch_assoc();
    $storedPassword = $row['pass']; // Stored password (no hashing)

    // Check if the entered password matches the stored password
    if ($pass === $storedPassword) {
        // Correct password
        session_start();
        $_SESSION['username'] = $user;
        $_SESSION['role'] = $row['role'];
        $_SESSION['loggedin'] = true;
        
        echo "success";
    } else {
        // Incorrect password
        echo "failure"; // Wrong password
    }
} else {
    // User not found
    echo "failure"; // User not found
}

$stmt->close();
$conn->close();
?>
