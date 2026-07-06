<?php
/**
 * Shared database connection for gateway PHP scripts.
 */

function gatewayDbConnect(): mysqli
{
    $servername = 'localhost';
    $username = 'startech_sms';
    $password = '101010';
    $dbname = 'startech_sms';

    $conn = mysqli_connect($servername, $username, $password, $dbname);
    if (!$conn) {
        throw new RuntimeException('Database connection failed: ' . mysqli_connect_error());
    }

    if (!mysqli_set_charset($conn, 'utf8mb4')) {
        throw new RuntimeException('Failed to set charset: ' . mysqli_error($conn));
    }

    return $conn;
}
