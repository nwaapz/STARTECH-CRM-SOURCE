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

/**
 * Mirror an M-prefix registration from Mcode into old_serials (seller inventory).
 * Inserts the serial if missing; updates phone/city/time/score when present.
 */
function syncMcodeRegistrationToOldSerials($conn, $serial, $phone, $city = null, $score = null)
{
    $serial = strtoupper(trim((string) $serial));
    if ($serial === '' || !isMprefixSerial($serial)) {
        return false;
    }
    if ($phone === null || trim((string) $phone) === '' || trim((string) $phone) === '0') {
        return false;
    }
    $phone = trim((string) $phone);
    $cityVal = ($city !== null && trim((string) $city) !== '') ? trim((string) $city) : '';
    $scoreVal = $score !== null ? (int) $score : (defined('STOM_MCODE_SCORE') ? (int) STOM_MCODE_SCORE : 10);
    $syncMs = (int) round(microtime(true) * 1000);
    $timeVal = date('Y-m-d');
    $category = 'seller_to_end_user';

    $tables = @$conn->query("SHOW TABLES LIKE 'old_serials'");
    if (!$tables || $tables->num_rows === 0) {
        return false;
    }

    $hasScore = false;
    $hasCategory = false;
    $hasSync = false;
    $cols = @$conn->query('SHOW COLUMNS FROM `old_serials`');
    if ($cols) {
        while ($col = $cols->fetch_assoc()) {
            $name = (string) ($col['Field'] ?? '');
            if ($name === 'score') {
                $hasScore = true;
            } elseif ($name === 'category') {
                $hasCategory = true;
            } elseif ($name === 'sync_updated_ms') {
                $hasSync = true;
            }
        }
    }

    $find = $conn->prepare('SELECT id FROM old_serials WHERE UPPER(serial) = ? LIMIT 1');
    if (!$find) {
        return false;
    }
    $find->bind_param('s', $serial);
    $find->execute();
    $existing = $find->get_result();
    $row = $existing ? $existing->fetch_assoc() : null;
    $find->close();

    if ($row) {
        if ($hasScore && $hasCategory && $hasSync && $cityVal !== '') {
            $sql = 'UPDATE old_serials SET phone = ?, time = ?, city = ?, score = GREATEST(IFNULL(score, 0), ?), category = ?, sync_updated_ms = ? WHERE UPPER(serial) = ?';
            $stmt = $conn->prepare($sql);
            if (!$stmt) {
                return false;
            }
            $stmt->bind_param('sssisis', $phone, $timeVal, $cityVal, $scoreVal, $category, $syncMs, $serial);
        } elseif ($hasScore && $hasCategory && $hasSync) {
            $sql = 'UPDATE old_serials SET phone = ?, time = ?, score = GREATEST(IFNULL(score, 0), ?), category = ?, sync_updated_ms = ? WHERE UPPER(serial) = ?';
            $stmt = $conn->prepare($sql);
            if (!$stmt) {
                return false;
            }
            $stmt->bind_param('ssisis', $phone, $timeVal, $scoreVal, $category, $syncMs, $serial);
        } elseif ($cityVal !== '') {
            $sql = 'UPDATE old_serials SET phone = ?, time = ?, city = ? WHERE UPPER(serial) = ?';
            $stmt = $conn->prepare($sql);
            if (!$stmt) {
                return false;
            }
            $stmt->bind_param('ssss', $phone, $timeVal, $cityVal, $serial);
        } else {
            $sql = 'UPDATE old_serials SET phone = ?, time = ? WHERE UPPER(serial) = ?';
            $stmt = $conn->prepare($sql);
            if (!$stmt) {
                return false;
            }
            $stmt->bind_param('sss', $phone, $timeVal, $serial);
        }
        $ok = $stmt->execute();
        $stmt->close();
        return $ok;
    }

    if ($hasScore && $hasCategory && $hasSync && $cityVal !== '') {
        $sql = 'INSERT INTO old_serials (serial, phone, time, city, score, category, sync_updated_ms) VALUES (?, ?, ?, ?, ?, ?, ?)';
        $stmt = $conn->prepare($sql);
        if (!$stmt) {
            return false;
        }
        $stmt->bind_param('ssssisi', $serial, $phone, $timeVal, $cityVal, $scoreVal, $category, $syncMs);
    } elseif ($hasScore && $hasCategory && $hasSync) {
        $sql = 'INSERT INTO old_serials (serial, phone, time, score, category, sync_updated_ms) VALUES (?, ?, ?, ?, ?, ?)';
        $stmt = $conn->prepare($sql);
        if (!$stmt) {
            return false;
        }
        $stmt->bind_param('sssisi', $serial, $phone, $timeVal, $scoreVal, $category, $syncMs);
    } elseif ($cityVal !== '') {
        $sql = 'INSERT INTO old_serials (serial, phone, time, city) VALUES (?, ?, ?, ?)';
        $stmt = $conn->prepare($sql);
        if (!$stmt) {
            return false;
        }
        $stmt->bind_param('ssss', $serial, $phone, $timeVal, $cityVal);
    } else {
        $sql = 'INSERT INTO old_serials (serial, phone, time) VALUES (?, ?, ?)';
        $stmt = $conn->prepare($sql);
        if (!$stmt) {
            return false;
        }
        $stmt->bind_param('sss', $serial, $phone, $timeVal);
    }
    $ok = $stmt->execute();
    $stmt->close();
    return $ok;
}
