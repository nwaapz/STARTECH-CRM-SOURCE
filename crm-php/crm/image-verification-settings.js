(function () {

    let loadedSettings = null;

    let initialized = false;

    let togglesLocked = false;



    function el(id) {

        return document.getElementById(id);

    }



    function applyToggleLock(locked) {

        togglesLocked = !!locked;

        ['iv-enable-s', 'iv-enable-m'].forEach((id) => {

            const node = el(id);

            if (!node) return;

            node.disabled = togglesLocked;

            if (togglesLocked) {

                node.checked = false;

            }

        });

        const notice = el('iv-toggles-lock-notice');

        if (notice) {

            if (togglesLocked) {

                notice.textContent = 'فعال‌سازی S و M موقتاً غیرفعال است. ثبت‌نام پیامکی بدون تأیید تصویر انجام می‌شود.';

                notice.style.display = '';

            } else {

                notice.textContent = '';

                notice.style.display = 'none';

            }

        }

        document.querySelectorAll('.iv-toggle-switch').forEach((wrap) => {

            wrap.classList.toggle('iv-toggle-locked', togglesLocked);

        });

    }



    function setStatus(text, isError) {

        const statusEl = el('iv-settings-status');

        if (!statusEl) return;

        statusEl.textContent = text || '';

        statusEl.classList.toggle('text-danger', !!isError);

        statusEl.classList.toggle('text-muted', !isError);

    }



    function snapshotFromSettings(settings) {

        return {

            enable_s: settings.enable_s ? 1 : 0,

            enable_m: settings.enable_m ? 1 : 0,

            testers_only: settings.testers_only ? 1 : 0,

            auto_verify_threshold: settings.auto_verify_threshold,

            sms_link_request: settings.sms_link_request,

            sms_verified: settings.sms_verified,

            sms_rejected: settings.sms_rejected,

            sms_rejected_unreadable: settings.sms_rejected_unreadable,

            sms_rejected_wrong_serial: settings.sms_rejected_wrong_serial,

            capture_base_url: settings.capture_base_url,

        };

    }



    function readForm() {

        const smsLink = el('iv-sms-link')?.value.trim();

        const smsVerified = el('iv-sms-verified')?.value.trim();

        const smsRejected = el('iv-sms-rejected')?.value.trim();

        const smsRejectedUnreadable = el('iv-sms-rejected-unreadable')?.value.trim();

        const smsRejectedWrongSerial = el('iv-sms-rejected-wrong-serial')?.value.trim();

        return {

            enable_s: togglesLocked ? 0 : (el('iv-enable-s')?.checked ? 1 : 0),

            enable_m: togglesLocked ? 0 : (el('iv-enable-m')?.checked ? 1 : 0),

            testers_only: el('iv-testers-only')?.checked ? 1 : 0,

            auto_verify_threshold: loadedSettings?.auto_verify_threshold ?? 85,

            sms_link_request: smsLink || loadedSettings?.sms_link_request || '',

            sms_verified: smsVerified || loadedSettings?.sms_verified || '',

            sms_rejected: smsRejected || loadedSettings?.sms_rejected || '',

            sms_rejected_unreadable: smsRejectedUnreadable || loadedSettings?.sms_rejected_unreadable || '',

            sms_rejected_wrong_serial: smsRejectedWrongSerial || loadedSettings?.sms_rejected_wrong_serial || '',

            capture_base_url: el('iv-capture-url')?.value.trim() || loadedSettings?.capture_base_url || '',

        };

    }



    function fillForm(settings) {

        applyToggleLock(settings.toggles_locked);

        if (el('iv-enable-s')) el('iv-enable-s').checked = togglesLocked ? false : !!settings.enable_s;

        if (el('iv-enable-m')) el('iv-enable-m').checked = togglesLocked ? false : !!settings.enable_m;

        if (el('iv-testers-only')) el('iv-testers-only').checked = !!settings.testers_only;

        if (el('iv-sms-link')) el('iv-sms-link').value = settings.sms_link_request || '';

        if (el('iv-sms-verified')) el('iv-sms-verified').value = settings.sms_verified || '';

        if (el('iv-sms-rejected')) el('iv-sms-rejected').value = settings.sms_rejected || '';

        if (el('iv-sms-rejected-unreadable')) el('iv-sms-rejected-unreadable').value = settings.sms_rejected_unreadable || '';

        if (el('iv-sms-rejected-wrong-serial')) el('iv-sms-rejected-wrong-serial').value = settings.sms_rejected_wrong_serial || '';

        if (el('iv-capture-url')) el('iv-capture-url').value = settings.capture_base_url || '';

    }



    function updateSaveButton() {

        const saveBtn = el('iv-settings-save-btn');

        if (!saveBtn || !loadedSettings) return;

        const current = readForm();

        const changed = JSON.stringify(current) !== JSON.stringify(snapshotFromSettings(loadedSettings));

        saveBtn.disabled = !changed;

        saveBtn.classList.toggle('btn-secondary', saveBtn.disabled);

        saveBtn.classList.toggle('btn-success', !saveBtn.disabled);

    }



    function bindEventsOnce() {

        if (initialized) return;

        initialized = true;

        [

            'iv-enable-s',

            'iv-enable-m',

            'iv-testers-only',

            'iv-sms-link',

            'iv-sms-verified',

            'iv-sms-rejected',

            'iv-sms-rejected-unreadable',

            'iv-sms-rejected-wrong-serial',

            'iv-capture-url',

        ].forEach((id) => {

            const node = el(id);

            if (node) {

                node.addEventListener('input', updateSaveButton);

                node.addEventListener('change', updateSaveButton);

            }

        });

        const saveBtn = el('iv-settings-save-btn');

        if (saveBtn) saveBtn.addEventListener('click', saveSettings);

    }



    function fetchSettings() {

        bindEventsOnce();

        setStatus('در حال بارگذاری...', false);

        return fetch('image-verification-settings.php', { credentials: 'same-origin' })

            .then((res) => res.json())

            .then((data) => {

                if (data.success && data.settings) {

                    loadedSettings = data.settings;

                    fillForm(data.settings);

                    updateSaveButton();

                    setStatus('', false);

                } else {

                    setStatus(data.message || 'خطا در بارگذاری', true);

                }

            })

            .catch(() => setStatus('خطا در ارتباط با سرور', true));

    }



    function saveSettings() {

        const payload = readForm();

        const saveBtn = el('iv-settings-save-btn');

        if (saveBtn) saveBtn.disabled = true;

        setStatus('در حال ذخیره...', false);

        fetch('image-verification-settings.php', {

            method: 'POST',

            credentials: 'same-origin',

            headers: { 'Content-Type': 'application/json' },

            body: JSON.stringify(payload),

        })

            .then((res) => res.json())

            .then((data) => {

                if (data.success) {

                    loadedSettings = data.settings;

                    fillForm(data.settings);

                    setStatus('ذخیره شد.', false);

                } else {

                    setStatus(data.message || 'خطا در ذخیره', true);

                }

                updateSaveButton();

            })

            .catch(() => {

                setStatus('خطا در ارتباط با سرور', true);

                updateSaveButton();

            });

    }



    window.initImageVerificationSettings = function () {

        fetchSettings();

    };

})();

