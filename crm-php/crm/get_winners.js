const PERSIAN_MONTHS = [
    'فروردین', 'اردیبهشت', 'خرداد', 'تیر', 'مرداد', 'شهریور',
    'مهر', 'آبان', 'آذر', 'دی', 'بهمن', 'اسفند'
];

function toPersianDigits(value) {
    return String(value).replace(/\d/g, d => '۰۱۲۳۴۵۶۷۸۹'[d]);
}

function formatJalaliMonthYear(dateTimeStr) {
    if (!dateTimeStr || typeof farvardin === 'undefined') {
        return dateTimeStr || '—';
    }

    const datePart = dateTimeStr.split(' ')[0];
    const parts = datePart.split('-').map(Number);
    if (parts.length < 3) {
        return dateTimeStr;
    }

    const solar = farvardin.gregorianToSolar(parts[0], parts[1], parts[2], 'object');
    const monthName = PERSIAN_MONTHS[solar.month - 1] || '';
    return `${monthName} ${toPersianDigits(solar.year)}`;
}

function formatJalaliDay(dateTimeStr) {
    if (!dateTimeStr || typeof farvardin === 'undefined') {
        return '—';
    }

    const datePart = dateTimeStr.split(' ')[0];
    const parts = datePart.split('-').map(Number);
    if (parts.length < 3) {
        return '—';
    }

    const solar = farvardin.gregorianToSolar(parts[0], parts[1], parts[2], 'object');
    return toPersianDigits(solar.day);
}

const DELETE_WINNER_CONFIRM_MSG = 'آیا این رکورد از تاریخچه برندگان حذف شود؟';

function deleteWinnerFromHistory(phone, time) {
    if (!confirm(DELETE_WINNER_CONFIRM_MSG)) {
        return Promise.resolve(null);
    }

    if (typeof lotteryLog === 'function') {
        lotteryLog('history_delete_request', { phone, time });
    }

    return fetch('delete-winner.php', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ phone, time })
    })
        .then(res => res.json())
        .then(data => {
            if (!data.success) {
                throw new Error(data.message || 'خطا در حذف رکورد');
            }
            if (typeof lotteryLog === 'function') {
                lotteryLog('history_delete_ok', { phone, time });
            }
            loadWinnersTable();
            return data;
        })
        .catch(err => {
            if (typeof lotteryLog === 'function') {
                lotteryLog('history_delete_error', { phone, time, message: err.message });
            }
            alert(err.message || 'خطا در حذف رکورد');
            throw err;
        });
}

function createWinnerDeleteButton(phone, time) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'btn btn-sm btn-outline-danger';
    btn.textContent = 'حذف';
    btn.title = 'حذف از تاریخچه برندگان';
    btn.addEventListener('click', e => {
        e.stopPropagation();
        deleteWinnerFromHistory(phone, time);
    });
    return btn;
}

function loadWinnersTable() {
    if (typeof lotteryLog === 'function') lotteryLog('history_fetch_request');
    fetch('get_winners.php')
        .then(res => res.json())
        .then(data => {
            const tbody = document.getElementById('lottery-history-body');
            const emptyMsg = document.getElementById('lottery-history-empty');
            if (!tbody) {
                if (typeof lotteryLog === 'function') lotteryLog('history_table_missing');
                return;
            }

            tbody.innerHTML = '';

            if (!data.length) {
                if (emptyMsg) emptyMsg.style.display = 'block';
                if (typeof lotteryLog === 'function') lotteryLog('history_empty');
                return;
            }

            if (emptyMsg) emptyMsg.style.display = 'none';

            data.forEach(winner => {
                const row = document.createElement('tr');
                row.innerHTML = `
                    <td>${winner.phone}</td>
                    <td>${formatJalaliMonthYear(winner.time)}</td>
                    <td>${formatJalaliDay(winner.time)}</td>
                `;

                const actionCell = document.createElement('td');
                actionCell.className = 'lottery-admin-action-cell';
                actionCell.appendChild(createWinnerDeleteButton(winner.phone, winner.time));
                row.appendChild(actionCell);

                tbody.appendChild(row);
            });
            if (typeof lotteryLog === 'function') lotteryLog('history_loaded', { count: data.length });
        })
        .catch(err => {
            console.error('خطا در دریافت لیست برندگان:', err);
            if (typeof lotteryLog === 'function') lotteryLog('history_fetch_error', { message: err.message });
        });
}

document.addEventListener('DOMContentLoaded', () => {
    if (typeof lotteryLog === 'function') lotteryLog('scripts_ready');
});
