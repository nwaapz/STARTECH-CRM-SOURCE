<?php

require_once __DIR__ . '/serial-utils.php';

define('STOM_MCODE_DESCRIPTION', 'S440-S450');
define('STOM_MCODE_SCORE', 10);

function syncSToMSerialToMcode($conn, $serial, $phone, $city = null, $score = STOM_MCODE_SCORE)
{
    if (!isSToMRange($serial)) {
        return false;
    }
    if ($phone === null || $phone === '' || $phone === '0') {
        return false;
    }

    $cityVal = ($city !== null && trim($city) !== '') ? trim($city) : '';

    $sql = "INSERT INTO Mcode (serial, phone, time, score, city, description)
            VALUES (?, ?, NOW(), ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                phone = VALUES(phone),
                time = VALUES(time),
                score = VALUES(score),
                city = IF(VALUES(city) = '', city, VALUES(city)),
                description = VALUES(description)";

    $stmt = $conn->prepare($sql);
    if (!$stmt) {
        return false;
    }

    $description = STOM_MCODE_DESCRIPTION;
    $stmt->bind_param('ssiss', $serial, $phone, $score, $cityVal, $description);
    $ok = $stmt->execute();
    $stmt->close();

    return $ok;
}

function insertSToMPreRegisterMcode($conn, $serial, $score = STOM_MCODE_SCORE)
{
    if (!isSToMRange($serial)) {
        return false;
    }

    $sql = "INSERT INTO Mcode (serial, phone, time, score, description)
            VALUES (?, '0', NOW(), ?, ?)
            ON DUPLICATE KEY UPDATE
                score = VALUES(score),
                description = VALUES(description)";

    $stmt = $conn->prepare($sql);
    if (!$stmt) {
        return false;
    }

    $description = STOM_MCODE_DESCRIPTION;
    $stmt->bind_param('sis', $serial, $score, $description);
    $ok = $stmt->execute();
    $stmt->close();

    return $ok;
}

function resetSToMSerialsForPhone($conn, $phone)
{
    if ($phone === '') {
        return 0;
    }

    $sql = "UPDATE old_serials
            SET phone = NULL, city = NULL, km = NULL, time = NULL
            WHERE phone = ?
            AND UPPER(serial) REGEXP '^S[0-9]+$'
            AND CAST(SUBSTRING(UPPER(serial), 2) AS UNSIGNED) BETWEEN 440000 AND 450000";

    $stmt = $conn->prepare($sql);
    if (!$stmt) {
        return 0;
    }

    $stmt->bind_param('s', $phone);
    $stmt->execute();
    $affected = $stmt->affected_rows;
    $stmt->close();

    return $affected;
}
