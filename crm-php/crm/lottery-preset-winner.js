(function () {
    const API_URL = 'lottery-preset-winner.php';

    function updateStatus(preset) {
        const statusEl = document.getElementById('lottery-preset-winner-status');
        if (!statusEl) return;
        if (preset) {
            statusEl.textContent = 'برنده از پیش تعیین‌شده (سرور): ' + preset;
            statusEl.classList.remove('text-muted');
            statusEl.classList.add('text-warning');
        } else {
            statusEl.textContent = 'برنده از پیش تعیین‌شده فعال نیست — قرعه‌کشی عادی';
            statusEl.classList.remove('text-warning');
            statusEl.classList.add('text-muted');
        }
    }

    function fetchLotteryPresetWinner(options) {
        const updateUi = !options || options.updateUi !== false;
        return fetch(API_URL, { credentials: 'same-origin' })
            .then(res => res.json())
            .then(data => {
                if (!data.success) {
                    throw new Error(data.message || 'خطا در دریافت تنظیمات');
                }
                if (updateUi) {
                    updateStatus(data.preset || null);
                }
                return data.preset || null;
            });
    }

    function postPresetAction(action, phone) {
        const body = { action };
        if (phone !== undefined) {
            body.phone = phone;
        }
        return fetch(API_URL, {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        }).then(res => res.json());
    }

    function savePresetWinner() {
        const input = document.getElementById('lottery-preset-winner-input');
        const phone = input ? input.value.trim() : '';
        if (!phone) {
            alert('شماره تلفن را وارد کنید.');
            return Promise.resolve(null);
        }
        return postPresetAction('set', phone)
            .then(data => {
                if (!data.success) {
                    throw new Error(data.message || 'خطا در ذخیره');
                }
                updateStatus(data.preset || null);
                if (typeof lotteryLog === 'function') {
                    lotteryLog('preset_winner_set', { phone: data.preset });
                }
                return data.preset || null;
            })
            .catch(err => {
                alert(err.message || 'خطا در ذخیره');
                throw err;
            });
    }

    function clearPresetWinner() {
        return postPresetAction('clear')
            .then(data => {
                if (!data.success) {
                    throw new Error(data.message || 'خطا در پاک کردن');
                }
                const input = document.getElementById('lottery-preset-winner-input');
                if (input) input.value = '';
                updateStatus(null);
                if (typeof lotteryLog === 'function') {
                    lotteryLog('preset_winner_cleared');
                }
                return null;
            })
            .catch(err => {
                alert(err.message || 'خطا در پاک کردن');
                throw err;
            });
    }

    document.addEventListener('DOMContentLoaded', function () {
        const section = document.getElementById('lottery-preset-winner-section');
        if (!section) return;

        const saveBtn = document.getElementById('lottery-preset-winner-save');
        const clearBtn = document.getElementById('lottery-preset-winner-clear');

        if (saveBtn && !saveBtn.dataset.bound) {
            saveBtn.dataset.bound = '1';
            saveBtn.addEventListener('click', savePresetWinner);
        }
        if (clearBtn && !clearBtn.dataset.bound) {
            clearBtn.dataset.bound = '1';
            clearBtn.addEventListener('click', clearPresetWinner);
        }

        fetchLotteryPresetWinner().catch(err => {
            console.error('خطا در بارگذاری برنده از پیش تعیین‌شده:', err);
        });
    });

    window.fetchLotteryPresetWinner = fetchLotteryPresetWinner;
    window.clearPresetWinner = clearPresetWinner;
})();
