function formatTestDataSummary(totals) {
    if (!totals) return '';
    return [
        `old_serials: ${totals.old_serials || 0}`,
        `new_serials: ${totals.new_serials || 0}`,
        `Mcode: ${totals.Mcode || 0}`,
        `winners: ${totals.winners || 0}`,
        `suspended: ${totals.lottery_suspended_phones || 0}`,
        `seller: ${totals.seller || 0}`
    ].join('\n');
}

function renderTestPhonesList(phones) {
    const listEl = document.getElementById('test-phones-list');
    const emptyEl = document.getElementById('test-phones-empty');
    if (!listEl) return;

    listEl.innerHTML = '';

    if (!phones || phones.length === 0) {
        if (emptyEl) emptyEl.style.display = 'block';
        return;
    }

    if (emptyEl) emptyEl.style.display = 'none';

    phones.forEach(item => {
        const li = document.createElement('li');
        li.className = 'list-group-item d-flex justify-content-between align-items-center';

        const label = document.createElement('span');
        const note = item.label ? ` — ${item.label}` : '';
        label.textContent = `${item.phone}${note}`;

        const removeBtn = document.createElement('button');
        removeBtn.type = 'button';
        removeBtn.className = 'btn btn-sm btn-outline-danger';
        removeBtn.textContent = 'حذف';
        removeBtn.addEventListener('click', () => removeTestPhone(item.phone));

        li.appendChild(label);
        li.appendChild(removeBtn);
        listEl.appendChild(li);
    });
}

function loadTestPhones() {
    return fetch('test-phones.php', { credentials: 'same-origin' })
        .then(res => res.json())
        .then(data => {
            if (!data.success) {
                throw new Error(data.message || 'خطا در دریافت لیست');
            }
            renderTestPhonesList(data.phones || []);
            return data;
        })
        .catch(err => {
            console.error('loadTestPhones:', err);
            const summary = document.getElementById('test-phones-summary');
            if (summary) {
                summary.textContent = 'خطا در بارگذاری شماره‌های تست: ' + err.message;
            }
        });
}

function addTestPhone() {
    const phoneInput = document.getElementById('test-phone-input');
    const labelInput = document.getElementById('test-phone-label');
    const phone = (phoneInput?.value || '').trim();
    const label = (labelInput?.value || '').trim();

    if (!phone) {
        alert('شماره تلفن را وارد کنید.');
        return;
    }

    fetch('test-phones.php', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'add', phone, label })
    })
        .then(res => res.json())
        .then(data => {
            if (!data.success) {
                throw new Error(data.message || 'خطا در ذخیره');
            }
            if (phoneInput) phoneInput.value = '';
            if (labelInput) labelInput.value = '';
            loadTestPhones();
        })
        .catch(err => alert('خطا: ' + err.message));
}

function removeTestPhone(phone) {
    if (!confirm(`شماره ${phone} از لیست تست حذف شود؟`)) {
        return;
    }

    fetch('test-phones.php', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'remove', phone })
    })
        .then(res => res.json())
        .then(data => {
            if (!data.success) {
                throw new Error(data.message || 'خطا در حذف');
            }
            loadTestPhones();
        })
        .catch(err => alert('خطا: ' + err.message));
}

function clearAllTestData() {
    const listItems = document.querySelectorAll('#test-phones-list li');
    const count = listItems.length;

    if (count === 0) {
        alert('ابتدا حداقل یک شماره تست اضافه کنید.');
        return;
    }

    const msg = `تمام داده‌های ثبت‌شده برای ${count} شماره تست پاکسازی می‌شود.\n\n` +
        'سریال‌ها آزاد می‌شوند و قابل ثبت مجدد خواهند بود.\n\nادامه می‌دهید؟';

    if (!confirm(msg)) {
        return;
    }

    const summary = document.getElementById('test-phones-summary');
    if (summary) summary.textContent = 'در حال پاکسازی...';

    fetch('clear-test-data.php', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({})
    })
        .then(res => res.json())
        .then(data => {
            if (!data.success) {
                throw new Error(data.message || 'خطا در پاکسازی');
            }
            const text = `${data.message}\n\n${formatTestDataSummary(data.totals)}`;
            if (summary) summary.textContent = text;
            alert(data.message + '\n\n' + formatTestDataSummary(data.totals));
            if (typeof fetchSellersData === 'function') {
                fetchSellersData(1);
            }
        })
        .catch(err => {
            if (summary) summary.textContent = 'خطا: ' + err.message;
            alert('خطا: ' + err.message);
        });
}

function initTestPhonesManager() {
    const addBtn = document.getElementById('test-phone-add-btn');
    const clearBtn = document.getElementById('test-phone-clear-btn');

    if (addBtn && !addBtn.dataset.bound) {
        addBtn.dataset.bound = '1';
        addBtn.addEventListener('click', addTestPhone);
    }

    if (clearBtn && !clearBtn.dataset.bound) {
        clearBtn.dataset.bound = '1';
        clearBtn.addEventListener('click', clearAllTestData);
    }
}

window.loadTestPhones = loadTestPhones;
window.initTestPhonesManager = initTestPhonesManager;
