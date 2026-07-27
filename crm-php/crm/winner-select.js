document.addEventListener('DOMContentLoaded', function () {
    const selectButton = document.getElementById('select-winner-button');
    const confirmButton = document.getElementById('confirm-winner-btn');
    const selectedPhoneDisplay = document.getElementById('selected-phone-display');
    const selectedScoreDisplay = document.getElementById('selected-score-display');
    const modalEl = document.getElementById('confirmWinnerModal');
    const drawPhase = document.getElementById('lottery-draw-phase');
    const confirmPhase = document.getElementById('lottery-confirm-phase');
    const confirmFooter = document.getElementById('lottery-confirm-footer');
    const closeBtn = document.getElementById('lottery-modal-close-btn');
    const drawStatus = document.getElementById('lottery-draw-status');
    const digitReel = document.getElementById('lottery-digit-reel');

    let selectedWinner = null;
    let cancelAnimation = null;
    let modalInstance = null;

    function resetModalUi() {
        if (drawPhase) drawPhase.style.display = '';
        if (confirmPhase) confirmPhase.style.display = 'none';
        if (confirmFooter) confirmFooter.style.display = 'none';
        if (closeBtn) closeBtn.style.display = 'none';
        if (drawStatus) drawStatus.textContent = 'در حال قرعه‌کشی…';
        if (digitReel) digitReel.innerHTML = '';
        const countdownEl = document.getElementById('lottery-countdown');
        if (countdownEl) {
            countdownEl.textContent = '30';
            countdownEl.classList.remove('is-finished');
        }
        if (selectedPhoneDisplay) selectedPhoneDisplay.textContent = '';
        if (selectedScoreDisplay) selectedScoreDisplay.textContent = '';
    }

    function showConfirmPhase() {
        if (drawPhase) drawPhase.style.display = '';
        if (confirmPhase) confirmPhase.style.display = 'block';
        if (confirmFooter) confirmFooter.style.display = '';
        if (closeBtn) closeBtn.style.display = '';
        const countdownEl = document.getElementById('lottery-countdown');
        if (countdownEl) countdownEl.classList.add('is-finished');
        if (selectedWinner) {
            selectedPhoneDisplay.textContent = selectedWinner.phone;
            selectedScoreDisplay.textContent = selectedWinner.score;
        }
        if (selectButton) selectButton.disabled = false;
    }

    function getModalInstance() {
        if (!modalEl) return null;
        return bootstrap.Modal.getOrCreateInstance(modalEl, {
            backdrop: 'static',
            keyboard: false
        });
    }

    function normalizePhoneKey(phone) {
        const digits = String(phone || '').replace(/\D/g, '');
        if (digits.length >= 10) {
            return digits.slice(-10);
        }
        return digits;
    }

    function buildWinnerFromPreset(presetPhone, pool) {
        const presetKey = normalizePhoneKey(presetPhone);
        const match = (pool || []).find(item => normalizePhoneKey(item.phone) === presetKey);
        return {
            phone: presetPhone,
            score: match ? match.score : 0
        };
    }

    function startDrawWithWinner(winner, pool) {
        if (typeof lotteryLog === 'function') {
            lotteryLog('draw_candidate', { phone: winner.phone, score: winner.score });
        }

        selectedWinner = winner;
        resetModalUi();

        modalInstance = getModalInstance();
        if (modalInstance) modalInstance.show();

        const animationPool = (pool && pool.length) ? pool : [{ phone: winner.phone }];

        if (typeof runLotteryDrawAnimation !== 'function') {
            showConfirmPhase();
            return;
        }

        cancelAnimation = runLotteryDrawAnimation({
            winnerPhone: winner.phone,
            participantPhones: animationPool,
            onComplete: showConfirmPhase,
            onCancel: () => {
                selectedWinner = null;
            }
        });
    }

    if (modalEl) {
        modalEl.addEventListener('hidden.bs.modal', () => {
            if (typeof cancelAnimation === 'function') {
                cancelAnimation();
                cancelAnimation = null;
            }
            selectedWinner = null;
            resetModalUi();
            if (selectButton) selectButton.disabled = false;
        });
    }

    selectButton.addEventListener('click', () => {
        if (typeof lotteryLog === 'function') lotteryLog('draw_button_click');

        if (typeof fetchDrawPool !== 'function' || typeof selectWinnerFromPool !== 'function') {
            alert('خطا در بارگذاری ماژول قرعه‌کشی.');
            return;
        }

        selectButton.disabled = true;

        const presetPromise = typeof fetchLotteryPresetWinner === 'function'
            ? fetchLotteryPresetWinner({ updateUi: false }).catch(() => null)
            : Promise.resolve(null);

        presetPromise.then(presetPhone => {
            return fetchDrawPool().then(pool => ({ presetPhone, pool }));
        }).then(({ presetPhone, pool }) => {
            if (presetPhone) {
                if (typeof lotteryLog === 'function') {
                    lotteryLog('draw_preset_winner', { phone: presetPhone });
                }
                startDrawWithWinner(buildWinnerFromPreset(presetPhone, pool), pool);
                return;
            }

            if (!pool || !pool.length) {
                if (typeof lotteryLog === 'function') lotteryLog('draw_no_winner');
                alert('شرکت‌کننده‌ای با امتیاز وجود ندارد.');
                selectButton.disabled = false;
                return;
            }

            const winner = selectWinnerFromPool(pool);
            if (!winner) {
                if (typeof lotteryLog === 'function') lotteryLog('draw_no_winner');
                alert('شرکت‌کننده‌ای با امتیاز وجود ندارد.');
                selectButton.disabled = false;
                return;
            }

            startDrawWithWinner(winner, pool);
        }).catch(error => {
            console.error('خطا در قرعه‌کشی:', error);
            alert('خطایی در ارتباط با سرور رخ داد.');
            selectButton.disabled = false;
        });
    });

    confirmButton.addEventListener('click', () => {
        if (!selectedWinner) return;

        fetch('winner-select.php', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                phone: selectedWinner.phone,
                winner: 'winner'
            })
        })
        .then(res => res.json())
        .then(data => {
            if (data.success) {
                if (typeof lotteryLog === 'function') {
                    lotteryLog('winner_saved', { phone: selectedWinner?.phone });
                }
                if (typeof fetchLotteryPresetWinner === 'function') {
                    fetchLotteryPresetWinner().catch(() => {});
                }
                alert('برنده با موفقیت ثبت شد و امتیاز امروز صفر شد.');
                selectedWinner = null;
                fetchSellersData();
                loadWinnersTable();
            } else {
                alert('خطا: ' + (data.message || 'مشکلی پیش آمده است.'));
            }
        })
        .catch(error => {
            console.error('خطا در ثبت برنده:', error);
            alert('ارسال با خطا مواجه شد.');
        });

        modalInstance = bootstrap.Modal.getInstance(modalEl);
        if (modalInstance) modalInstance.hide();
    });
});
