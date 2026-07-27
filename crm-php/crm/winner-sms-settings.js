(function () {
    let loadedMessage = '';
    let initialized = false;

    function getTextarea() {
        return document.getElementById('winner-sms-message');
    }

    function getSaveBtn() {
        return document.getElementById('winner-sms-save-btn');
    }

    function getTestBtn() {
        return document.getElementById('winner-sms-test-btn');
    }

    function getStatusEl() {
        return document.getElementById('winner-sms-status');
    }

    function setStatus(text, isError) {
        const statusEl = getStatusEl();
        if (!statusEl) return;
        statusEl.textContent = text || '';
        statusEl.classList.toggle('text-danger', !!isError);
        statusEl.classList.toggle('text-muted', !isError);
    }

    function updateSaveButtonState() {
        const textarea = getTextarea();
        const saveBtn = getSaveBtn();
        if (!textarea || !saveBtn) return;

        const changed = textarea.value.trim() !== loadedMessage.trim();
        saveBtn.disabled = !changed || textarea.value.trim() === '';
        saveBtn.classList.toggle('btn-secondary', saveBtn.disabled);
        saveBtn.classList.toggle('btn-success', !saveBtn.disabled);
    }

    function bindEventsOnce() {
        if (initialized) return;
        initialized = true;

        const textarea = getTextarea();
        const saveBtn = getSaveBtn();
        const testBtn = getTestBtn();

        if (textarea) {
            textarea.addEventListener('input', updateSaveButtonState);
        }

        if (saveBtn) {
            saveBtn.addEventListener('click', saveWinnerSmsMessage);
        }

        if (testBtn) {
            testBtn.addEventListener('click', testSendWinnerSms);
        }

        if (typeof userRole !== 'undefined' && userRole >= 5 && testBtn) {
            testBtn.style.display = '';
        }
    }

    function fetchWinnerSmsMessage() {
        bindEventsOnce();
        setStatus('در حال بارگذاری...', false);

        return fetch('winner-sms-settings.php', { credentials: 'same-origin' })
            .then(res => res.json())
            .then(data => {
                const textarea = getTextarea();
                if (!textarea) return;

                if (data.success) {
                    loadedMessage = data.message || '';
                    textarea.value = loadedMessage;
                    updateSaveButtonState();
                    setStatus('', false);
                } else {
                    setStatus('خطا در بارگذاری پیامک برنده: ' + (data.message || 'نامشخص'), true);
                }
            })
            .catch(err => {
                setStatus('خطا در بارگذاری پیامک برنده: ' + err.message, true);
            });
    }

    function saveWinnerSmsMessage() {
        const textarea = getTextarea();
        if (!textarea) return;

        const message = textarea.value.trim();
        if (!message) {
            alert('متن پیامک نمی‌تواند خالی باشد.');
            return;
        }

        const saveBtn = getSaveBtn();
        if (saveBtn) saveBtn.disabled = true;

        fetch('winner-sms-settings.php', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message })
        })
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    loadedMessage = message;
                    updateSaveButtonState();
                    setStatus('پیامک برنده با موفقیت ذخیره شد.', false);
                    alert('پیامک برنده با موفقیت ذخیره شد.');
                } else {
                    setStatus('خطا در ذخیره: ' + (data.message || 'نامشخص'), true);
                    alert('خطا در ذخیره: ' + (data.message || 'نامشخص'));
                    updateSaveButtonState();
                }
            })
            .catch(err => {
                setStatus('خطا در ذخیره: ' + err.message, true);
                alert('خطا در ذخیره: ' + err.message);
                updateSaveButtonState();
            });
    }

    function testSendWinnerSms() {
        const textarea = getTextarea();
        const message = textarea?.value.trim() || loadedMessage.trim();
        if (!message) {
            alert('ابتدا متن پیامک را وارد و ذخیره کنید.');
            return;
        }

        if (textarea && textarea.value.trim() !== loadedMessage.trim()) {
            alert('ابتدا تغییرات را ذخیره کنید، سپس ارسال آزمایشی انجام دهید.');
            return;
        }

        if (!confirm('پیامک برنده به همه شماره‌های تست ارسال شود؟')) {
            return;
        }

        const testBtn = getTestBtn();
        if (testBtn) testBtn.disabled = true;
        setStatus('در حال ارسال آزمایشی...', false);

        fetch('test-send-winner-sms.php', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({})
        })
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    setStatus(data.message || 'ارسال آزمایشی انجام شد.', false);
                    alert(data.message || 'ارسال آزمایشی انجام شد.');
                } else {
                    setStatus(data.message || 'ارسال آزمایشی ناموفق بود.', true);
                    alert(data.message || 'ارسال آزمایشی ناموفق بود.');
                }
            })
            .catch(err => {
                setStatus('خطا در ارسال آزمایشی: ' + err.message, true);
                alert('خطا در ارسال آزمایشی: ' + err.message);
            })
            .finally(() => {
                if (testBtn) testBtn.disabled = false;
            });
    }

    window.initWinnerSmsSettings = fetchWinnerSmsMessage;
})();
