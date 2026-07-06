<?php
/**
 * Gregorian to Jalali (Persian) date conversion.
 */

function gregorianToJalali(int $gy, int $gm, int $gd): array
{
    $g_d_m = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334];
    $gy2 = ($gm > 2) ? ($gy + 1) : $gy;
    $days = 355666
        + (365 * $gy)
        + intdiv($gy2 + 3, 4)
        - intdiv($gy2 + 99, 100)
        + intdiv($gy2 + 399, 400)
        + $gd
        + $g_d_m[$gm - 1];
    $jy = -1595 + (33 * intdiv($days, 12053));
    $days %= 12053;
    $jy += 4 * intdiv($days, 1461);
    $days %= 1461;
    if ($days > 365) {
        $jy += intdiv($days - 1, 365);
        $days = ($days - 1) % 365;
    }
    if ($days < 186) {
        $jm = 1 + intdiv($days, 31);
        $jd = 1 + ($days % 31);
    } else {
        $jm = 7 + intdiv($days - 186, 30);
        $jd = 1 + (($days - 186) % 30);
    }

    return [$jy, $jm, $jd];
}

function formatJalaliDate(int $year, int $month, int $day): string
{
    return sprintf('%04d/%02d/%02d', $year, $month, $day);
}

function todayJalaliDate(): string
{
    [$jy, $jm, $jd] = gregorianToJalali(
        (int) date('Y'),
        (int) date('n'),
        (int) date('j')
    );

    return formatJalaliDate($jy, $jm, $jd);
}

function gregorianStringToJalali(string $ymd): string
{
    $dt = DateTime::createFromFormat('Y-m-d', $ymd);
    $errors = DateTime::getLastErrors();
    if (
        !$dt
        || ($errors['warning_count'] ?? 0) > 0
        || ($errors['error_count'] ?? 0) > 0
    ) {
        throw new InvalidArgumentException('Invalid date. Use YYYY-MM-DD format.');
    }

    [$jy, $jm, $jd] = gregorianToJalali(
        (int) $dt->format('Y'),
        (int) $dt->format('n'),
        (int) $dt->format('j')
    );

    return formatJalaliDate($jy, $jm, $jd);
}
