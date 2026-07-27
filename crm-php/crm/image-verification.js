(function () {
    let initialized = false;
    let currentFilter = 'pending';
    let currentPage = 1;
    let activeRow = null;

    const REJECT_LABELS = {
        unreadable: 'اخطار: تصویر ناخوانا',
        wrong_serial: 'اخطار: سریال نامرتبط',
    };

    function el(id) {
        return document.getElementById(id);
    }

    function toPersianDigits(str) {
        const eng = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9'];
        const per = ['۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹'];
        return String(str).replace(/[0-9]/g, (d) => per[eng.indexOf(d)]);
    }

    function formatRelativeTime(iso) {
        if (!iso) return '';
        const then = new Date(iso.replace(' ', 'T'));
        const diffSec = Math.floor((Date.now() - then.getTime()) / 1000);
        if (diffSec < 60) return 'لحظاتی';
        const min = Math.floor(diffSec / 60);
        if (min < 60) return toPersianDigits(min) + ' دقیقه';
        const hr = Math.floor(min / 60);
        if (hr < 24) return toPersianDigits(hr) + ' ساعت';
        const day = Math.floor(hr / 24);
        return toPersianDigits(day) + ' روز';
    }

    function statusLabel(status) {
        const map = {
            pending_capture: 'در انتظار تصویر',
            pending_review: 'در انتظار بررسی',
            auto_verified: 'تأیید شده',
            verified: 'تأیید شده',
            rejected: 'رد شده',
        };
        return map[status] || status;
    }

    function isReviewable(row) {
        return row.status === 'pending_review';
    }

    function setListStatus(msg, isError) {
        const node = el('iv-list-status');
        if (!node) return;
        node.textContent = msg || '';
        node.classList.toggle('text-danger', !!isError);
        node.classList.toggle('text-muted', !isError);
    }

    function setActiveFilter(filter) {
        currentFilter = filter;
        ['pending', 'verified', 'all'].forEach((f) => {
            const btn = el('iv-filter-' + f);
            if (!btn) return;
            btn.classList.toggle('active', f === filter);
            btn.classList.toggle('btn-primary', f === filter);
            btn.classList.toggle('btn-outline-secondary', f !== filter);
        });
    }

    function renderRows(rows) {
        const tbody = el('iv-table-body');
        const empty = el('iv-table-empty');
        if (!tbody) return;
        tbody.innerHTML = '';
        if (!rows.length) {
            if (empty) empty.style.display = '';
            return;
        }
        if (empty) empty.style.display = 'none';

        rows.forEach((row) => {
            const tr = document.createElement('tr');
            const dt = row.submitted_at || row.verified_at || row.created_at || '—';
            const imageBtn = row.has_image
                ? `<button type="button" class="btn btn-sm btn-primary iv-view-btn" data-id="${row.id}">مشاهده تصویر</button>`
                : '—';
            tr.innerHTML = `
                <td>${row.serial || '—'}</td>
                <td>${row.phone || '—'}</td>
                <td>${row.city || '—'}</td>
                <td>${row.km != null && row.km !== '' && Number(row.km) > 0 ? toPersianDigits(row.km) : '—'}</td>
                <td>${row.code_type || '—'}</td>
                <td>${dt}</td>
                <td>${statusLabel(row.status)}</td>
                <td>${row.reject_count > 0 ? toPersianDigits(row.reject_count) : '—'}</td>
                <td>${imageBtn}</td>`;
            tbody.appendChild(tr);
        });

        tbody.querySelectorAll('.iv-view-btn').forEach((btn) => {
            btn.addEventListener('click', () => {
                const id = parseInt(btn.getAttribute('data-id'), 10);
                const row = rows.find((r) => r.id === id);
                if (row) openReviewModal(row);
            });
        });
    }

    function loadList(page) {
        currentPage = page || 1;
        setListStatus('در حال بارگذاری...', false);
        const url = `image-verification-list.php?filter=${encodeURIComponent(currentFilter)}&page=${currentPage}`;
        fetch(url, { credentials: 'same-origin' })
            .then((res) => res.json())
            .then((data) => {
                if (!data.success) {
                    setListStatus(data.message || 'خطا', true);
                    return;
                }
                renderRows(data.rows || []);
                const pag = data.pagination || {};
                const info = el('iv-page-info');
                if (info) {
                    info.textContent = pag.total_pages
                        ? `صفحه ${toPersianDigits(pag.page)} از ${toPersianDigits(pag.total_pages)} (${toPersianDigits(pag.total)} مورد)`
                        : 'موردی یافت نشد';
                }
                const prev = el('iv-prev-page');
                const next = el('iv-next-page');
                if (prev) prev.disabled = pag.page <= 1;
                if (next) next.disabled = pag.page >= pag.total_pages;
                setListStatus('', false);
            })
            .catch(() => setListStatus('خطا در ارتباط با سرور', true));
    }

    function updateRejectButtonLabels(row) {
        const unreadableBtn = el('iv-reject-unreadable-btn');
        const wrongSerialBtn = el('iv-reject-wrong-serial-btn');
        const ago = row.last_reject_at ? formatRelativeTime(row.last_reject_at) + ' پیش' : '';

        if (unreadableBtn) {
            unreadableBtn.textContent = REJECT_LABELS.unreadable;
            if (row.last_reject_at && row.last_reject_reason === 'unreadable') {
                unreadableBtn.textContent += ' (' + ago + ' ارسال شد)';
            }
        }
        if (wrongSerialBtn) {
            wrongSerialBtn.textContent = REJECT_LABELS.wrong_serial;
            if (row.last_reject_at && row.last_reject_reason === 'wrong_serial') {
                wrongSerialBtn.textContent += ' (' + ago + ' ارسال شد)';
            }
        }
    }

    function setReviewActions(row) {
        const reviewable = isReviewable(row);
        const verifyBtn = el('iv-verify-btn');
        const unreadableBtn = el('iv-reject-unreadable-btn');
        const wrongSerialBtn = el('iv-reject-wrong-serial-btn');
        const title = el('iv-review-title');
        if (verifyBtn) verifyBtn.style.display = reviewable ? '' : 'none';
        if (unreadableBtn) unreadableBtn.style.display = reviewable ? '' : 'none';
        if (wrongSerialBtn) wrongSerialBtn.style.display = reviewable ? '' : 'none';
        if (title) {
            title.textContent = reviewable ? 'بررسی تصویر سریال' : 'تصویر ثبت‌شده سریال';
        }
    }

    function openReviewModal(row) {
        activeRow = row;
        const overlay = el('iv-review-overlay');
        if (!overlay) return;

        el('iv-meta-serial').textContent = row.serial || '—';
        el('iv-meta-phone').textContent = row.phone || '—';
        el('iv-meta-city').textContent = row.city || '—';
        el('iv-meta-km').textContent = row.km != null && Number(row.km) > 0 ? toPersianDigits(row.km) : '—';
        el('iv-meta-date').textContent = row.submitted_at || row.verified_at || row.created_at || '—';
        el('iv-meta-status').textContent = statusLabel(row.status);

        const img = el('iv-review-image');
        if (img) {
            img.src = 'image-verification-image.php?id=' + row.id + '&_=' + Date.now();
        }

        setReviewActions(row);
        if (isReviewable(row)) {
            updateRejectButtonLabels(row);
        }
        el('iv-review-status').textContent = '';
        overlay.style.display = 'flex';
    }

    function closeReviewModal() {
        const overlay = el('iv-review-overlay');
        if (overlay) overlay.style.display = 'none';
        activeRow = null;
    }

    function postReview(action) {
        if (!activeRow || !isReviewable(activeRow)) return;
        const statusEl = el('iv-review-status');
        if (statusEl) statusEl.textContent = 'در حال پردازش...';

        fetch('image-verification-review.php', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id: activeRow.id, action }),
        })
            .then((res) => res.json())
            .then((data) => {
                if (!data.success) {
                    if (statusEl) statusEl.textContent = data.message || 'خطا';
                    return;
                }
                if (action === 'verify') {
                    closeReviewModal();
                    loadList(currentPage);
                    return;
                }
                activeRow.last_reject_at = data.last_reject_at;
                activeRow.last_reject_reason = data.last_reject_reason;
                activeRow.reject_count = data.reject_count;
                updateRejectButtonLabels(activeRow);
                if (statusEl) statusEl.textContent = data.message || 'اخطار ارسال شد';
                loadList(currentPage);
            })
            .catch(() => {
                if (statusEl) statusEl.textContent = 'خطا در ارتباط با سرور';
            });
    }

    function bindEventsOnce() {
        if (initialized) return;
        initialized = true;

        el('iv-filter-pending')?.addEventListener('click', () => {
            setActiveFilter('pending');
            loadList(1);
        });
        el('iv-filter-verified')?.addEventListener('click', () => {
            setActiveFilter('verified');
            loadList(1);
        });
        el('iv-filter-all')?.addEventListener('click', () => {
            setActiveFilter('all');
            loadList(1);
        });
        el('iv-prev-page')?.addEventListener('click', () => {
            if (currentPage > 1) loadList(currentPage - 1);
        });
        el('iv-next-page')?.addEventListener('click', () => loadList(currentPage + 1));
        el('iv-refresh-btn')?.addEventListener('click', () => loadList(currentPage));
        el('iv-close-btn')?.addEventListener('click', closeReviewModal);
        el('iv-verify-btn')?.addEventListener('click', () => postReview('verify'));
        el('iv-reject-unreadable-btn')?.addEventListener('click', () => postReview('reject_unreadable'));
        el('iv-reject-wrong-serial-btn')?.addEventListener('click', () => postReview('reject_wrong_serial'));
        el('iv-review-overlay')?.addEventListener('click', (e) => {
            if (e.target === el('iv-review-overlay')) closeReviewModal();
        });
    }

    window.initImageVerification = function () {
        bindEventsOnce();
        setActiveFilter(currentFilter);
        loadList(1);
    };
})();
