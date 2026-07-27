<?php
$servername = "localhost";
$username = "startech_sms";
$password = "101010";
$dbname ="startech_sms";


// Create connection
$conn = new mysqli($servername, $username, $password, $dbname);

if (!$conn) {
    die("Connection failed: " . mysqli_connect_error());
} else {
    
}

$OldSerialTable = "old_serials";
$oldserialTable_serial = "serial";
$oldserialTable_date = "time";
$oldserialTable_phone = "phone";
$oldserialTable_km = "km";

// Check connection
if ($conn->connect_error) {
    die("Connection failed: " . $conn->connect_error);
}

// Optional: Set character set to utf8mb4 for better compatibility
$conn->set_charset("utf8mb4");
?>
