const RESET_CONFIRM_MSG =
    'آیا مطمئن هستید؟ تمام اطلاعات این شماره پاک می‌شود و سریال‌ها برای ثبت مجدد آزاد می‌شوند.';

const SUSPEND_CONFIRM_MSG =
    'آیا مطمئن هستید؟ این شماره از قرعه‌کشی حذف می‌شود.';

const UNSUSPEND_CONFIRM_MSG =
    'آیا مطمئن هستید؟ تعلیق این شماره برداشته شود و دوباره در قرعه‌کشی شرکت کند؟';

window.RESET_CONFIRM_MSG = RESET_CONFIRM_MSG;
window.SUSPEND_CONFIRM_MSG = SUSPEND_CONFIRM_MSG;
window.UNSUSPEND_CONFIRM_MSG = UNSUSPEND_CONFIRM_MSG;

function postLotteryAdminAction(url, body) {
    return fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    }).then(res => res.json());
}

function resetLotteryClient(phone) {
    if (typeof lotteryLog === 'function') lotteryLog('reset_client_request', { phone });
    return postLotteryAdminAction('lottery-reset-client.php', { phone })
        .then(data => {
            if (!data.success) {
                throw new Error(data.message || 'خطا در بازنشانی');
            }
            if (typeof lotteryLog === 'function') {
                lotteryLog('reset_client_ok', { phone, rowsReset: data.rowsReset });
            }
            return data;
        });
}

function suspendLotteryClient(phone, suspend) {
    if (typeof lotteryLog === 'function') {
        lotteryLog(suspend ? 'suspend_client_request' : 'unsuspend_client_request', { phone });
    }
    return postLotteryAdminAction('lottery-suspend-client.php', { phone, suspend })
        .then(data => {
            if (!data.success) {
                throw new Error(data.message || 'خطا در تعلیق');
            }
            if (typeof lotteryLog === 'function') {
                lotteryLog(suspend ? 'suspend_client_ok' : 'unsuspend_client_ok', { phone });
            }
            return data;
        });
}

function confirmAndResetClient(phone) {
    if (!confirm(window.RESET_CONFIRM_MSG)) {
        return Promise.resolve(null);
    }
    return resetLotteryClient(phone)
        .then(data => {
            refreshLotteryAfterAdminAction();
            if (typeof closePopup === 'function') closePopup();
            return data;
        })
        .catch(err => {
            alert(err.message || 'خطا در بازنشانی');
            throw err;
        });
}

function confirmAndSuspendClient(phone) {
    if (!confirm(window.SUSPEND_CONFIRM_MSG)) {
        return Promise.resolve(null);
    }
    return suspendLotteryClient(phone, true)
        .then(data => {
            refreshLotteryAfterAdminAction();
            if (typeof closePopup === 'function') closePopup();
            return data;
        })
        .catch(err => {
            alert(err.message || 'خطا در تعلیق');
            throw err;
        });
}

function confirmAndUnsuspendClient(phone) {
    if (!confirm(window.UNSUSPEND_CONFIRM_MSG)) {
        return Promise.resolve(null);
    }
    return suspendLotteryClient(phone, false)
        .then(data => {
            refreshLotteryAfterAdminAction();
            return data;
        })
        .catch(err => {
            alert(err.message || 'خطا در برداشتن تعلیق');
            throw err;
        });
}

function createLotteryAdminButtons(phone) {
    const wrap = document.createElement('div');
    wrap.className = 'lottery-admin-btns';

    const resetBtn = document.createElement('button');
    resetBtn.type = 'button';
    resetBtn.className = 'btn btn-sm btn-warning lottery-reset-btn';
    resetBtn.textContent = 'بازنشانی';
    resetBtn.title = 'آزادسازی سریال‌ها برای مشتری جدید';
    resetBtn.addEventListener('click', e => {
        e.stopPropagation();
        confirmAndResetClient(phone);
    });

    const suspendBtn = document.createElement('button');
    suspendBtn.type = 'button';
    suspendBtn.className = 'btn btn-sm btn-danger lottery-suspend-btn';
    suspendBtn.textContent = 'تعلیق';
    suspendBtn.title = 'حذف از قرعه‌کشی';
    suspendBtn.addEventListener('click', e => {
        e.stopPropagation();
        confirmAndSuspendClient(phone);
    });

    wrap.appendChild(resetBtn);
    wrap.appendChild(suspendBtn);
    return wrap;
}

function refreshLotteryAfterAdminAction() {
    const page = typeof lotteryCurrentPage !== 'undefined' ? lotteryCurrentPage : 1;
    const tasks = [];
    if (typeof fetchSellersData === 'function') {
        tasks.push(fetchSellersData(page));
    }
    if (typeof loadSuspendedPhones === 'function') {
        tasks.push(loadSuspendedPhones());
    }
    return Promise.all(tasks);
}

function loadSuspendedPhones() {
    if (typeof lotteryLog === 'function') lotteryLog('suspended_list_request');
    return fetch('get-suspended-phones.php')
        .then(res => res.json())
        .then(data => {
            const tbody = document.getElementById('lottery-suspended-body');
            const emptyMsg = document.getElementById('lottery-suspended-empty');
            if (!tbody) return data;

            tbody.innerHTML = '';

            if (!data.success || !data.data?.length) {
                if (emptyMsg) emptyMsg.style.display = 'block';
                if (typeof lotteryLog === 'function') lotteryLog('suspended_list_empty');
                return data;
            }

            if (emptyMsg) emptyMsg.style.display = 'none';

            data.data.forEach(item => {
                const tr = document.createElement('tr');
                const tdPhone = document.createElement('td');
                tdPhone.textContent = item.phone;

                const tdAction = document.createElement('td');
                tdAction.className = 'lottery-admin-action-cell';

                const unsuspendBtn = document.createElement('button');
                unsuspendBtn.type = 'button';
                unsuspendBtn.className = 'btn btn-sm btn-outline-success';
                unsuspendBtn.textContent = 'برداشتن تعلیق';
                unsuspendBtn.addEventListener('click', e => {
                    e.stopPropagation();
                    confirmAndUnsuspendClient(item.phone);
                });
                tdAction.appendChild(unsuspendBtn);

                tr.appendChild(tdPhone);
                tr.appendChild(tdAction);
                tbody.appendChild(tr);
            });

            if (typeof lotteryLog === 'function') {
                lotteryLog('suspended_list_loaded', { count: data.data.length });
            }
            return data;
        })
        .catch(err => {
            console.error('خطا در دریافت لیست تعلیق‌شده‌ها:', err);
            if (typeof lotteryLog === 'function') {
                lotteryLog('suspended_list_error', { message: err.message });
            }
        });
}

window.resetLotteryClient = resetLotteryClient;
window.suspendLotteryClient = suspendLotteryClient;
window.loadSuspendedPhones = loadSuspendedPhones;
window.refreshLotteryAfterAdminAction = refreshLotteryAfterAdminAction;
window.confirmAndResetClient = confirmAndResetClient;
window.confirmAndSuspendClient = confirmAndSuspendClient;
window.confirmAndUnsuspendClient = confirmAndUnsuspendClient;
window.createLotteryAdminButtons = createLotteryAdminButtons;

function runStomLotteryBackfill() {
    if (!confirm('سریال‌های ثبت‌شده S440000 تا S450000 از old_serials به قرعه‌کشی (Mcode) همگام‌سازی شوند؟')) {
        return Promise.resolve(null);
    }
    if (typeof lotteryLog === 'function') lotteryLog('stom_backfill_request');
    return fetch('backfill-stom-lottery.php', { credentials: 'same-origin' })
        .then(res => res.json())
        .then(data => {
            if (!data.success) {
                throw new Error(data.message || 'خطا در همگام‌سازی');
            }
            if (typeof lotteryLog === 'function') {
                lotteryLog('stom_backfill_ok', { synced: data.synced, failed: data.failed });
            }
            alert(data.message || 'همگام‌سازی انجام شد');
            if (typeof fetchSellersData === 'function') {
                fetchSellersData(1);
            }
            return data;
        })
        .catch(err => {
            alert('خطا: ' + err.message);
            if (typeof lotteryLog === 'function') {
                lotteryLog('stom_backfill_error', { message: err.message });
            }
        });
}

document.addEventListener('DOMContentLoaded', function () {
    const backfillBtn = document.getElementById('stom-backfill-button');
    if (backfillBtn && !backfillBtn.dataset.bound) {
        backfillBtn.dataset.bound = '1';
        backfillBtn.addEventListener('click', runStomLotteryBackfill);
    }
});

window.runStomLotteryBackfill = runStomLotteryBackfill;
