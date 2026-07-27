(function () {
    const PHONE_DIGIT_COUNT = 11;
    const MASKED_INDICES = [4, 5, 6];
    const GROUP_GAP_AFTER = [3, 6];
    const TOTAL_DURATION_MS = 30000;
    const CYCLE_INTERVAL_MS = 300;
    const COUNTDOWN_START = 30;

    let audioCtx = null;

    function ensureAudioContext() {
        try {
            if (!audioCtx) {
                audioCtx = new (window.AudioContext || window.webkitAudioContext)();
            }
            if (audioCtx.state === 'suspended') {
                audioCtx.resume();
            }
            return audioCtx;
        } catch (e) {
            return null;
        }
    }

    function normalizePhoneDigits(phone) {
        let digits = String(phone || '').replace(/\D/g, '');
        if (digits.length > PHONE_DIGIT_COUNT) {
            digits = digits.slice(-PHONE_DIGIT_COUNT);
        }
        while (digits.length < PHONE_DIGIT_COUNT) {
            digits = '0' + digits;
        }
        return digits.split('');
    }

    function extractPhones(participantPhones) {
        return (participantPhones || [])
            .map(p => (typeof p === 'object' && p !== null ? p.phone : p))
            .filter(p => String(p || '').replace(/\D/g, '').length > 0);
    }

    function pickRandomPhone(phones) {
        if (!phones.length) return null;
        return phones[Math.floor(Math.random() * phones.length)];
    }

    function playTickSound() {
        const ctx = ensureAudioContext();
        if (!ctx) return;

        const now = ctx.currentTime;
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.type = 'square';
        osc.frequency.setValueAtTime(920, now);
        gain.gain.setValueAtTime(0.045, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.06);
        osc.start(now);
        osc.stop(now + 0.06);
    }

    function playConfettiSound() {
        const ctx = ensureAudioContext();
        if (!ctx) return;

        const now = ctx.currentTime;
        const masterGain = ctx.createGain();
        masterGain.connect(ctx.destination);
        masterGain.gain.setValueAtTime(0.28, now);
        masterGain.gain.exponentialRampToValueAtTime(0.001, now + 1.6);

        const fanfare = [
            { freq: 523.25, delay: 0, type: 'triangle', peak: 0.32 },
            { freq: 659.25, delay: 0.09, type: 'triangle', peak: 0.34 },
            { freq: 783.99, delay: 0.18, type: 'triangle', peak: 0.36 },
            { freq: 1046.5, delay: 0.27, type: 'sine', peak: 0.38 },
            { freq: 1318.51, delay: 0.36, type: 'sine', peak: 0.4 }
        ];

        fanfare.forEach(({ freq, delay, type, peak }) => {
            const start = now + delay;
            const osc = ctx.createOscillator();
            const gain = ctx.createGain();
            osc.type = type;
            osc.frequency.setValueAtTime(freq, start);
            gain.gain.setValueAtTime(0.001, start);
            gain.gain.linearRampToValueAtTime(peak, start + 0.04);
            gain.gain.exponentialRampToValueAtTime(0.001, start + 0.42);
            osc.connect(gain);
            gain.connect(masterGain);
            osc.start(start);
            osc.stop(start + 0.45);
        });

        [1046.5, 1318.51, 1567.98, 2093].forEach((freq, index) => {
            const start = now + 0.52 + index * 0.02;
            const osc = ctx.createOscillator();
            const gain = ctx.createGain();
            osc.type = 'sine';
            osc.frequency.setValueAtTime(freq, start);
            gain.gain.setValueAtTime(0.001, start);
            gain.gain.linearRampToValueAtTime(0.1, start + 0.05);
            gain.gain.exponentialRampToValueAtTime(0.001, start + 1.1);
            osc.connect(gain);
            gain.connect(masterGain);
            osc.start(start);
            osc.stop(start + 1.15);
        });

        const bufferSize = Math.floor(ctx.sampleRate * 0.18);
        const buffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate);
        const data = buffer.getChannelData(0);
        for (let i = 0; i < bufferSize; i++) {
            data[i] = (Math.random() * 2 - 1) * (1 - i / bufferSize);
        }

        const noise = ctx.createBufferSource();
        noise.buffer = buffer;
        const noiseFilter = ctx.createBiquadFilter();
        noiseFilter.type = 'bandpass';
        noiseFilter.frequency.setValueAtTime(5200, now + 0.4);
        noiseFilter.Q.value = 0.8;
        const noiseGain = ctx.createGain();
        noiseGain.gain.setValueAtTime(0.001, now + 0.4);
        noiseGain.gain.linearRampToValueAtTime(0.12, now + 0.43);
        noiseGain.gain.exponentialRampToValueAtTime(0.001, now + 0.75);
        noise.connect(noiseFilter);
        noiseFilter.connect(noiseGain);
        noiseGain.connect(masterGain);
        noise.start(now + 0.4);
        noise.stop(now + 0.78);
    }

    function fireConfettiBurst() {
        playConfettiSound();

        if (typeof confetti !== 'function') return;

        const duration = 2500;
        const end = Date.now() + duration;

        (function frame() {
            confetti({
                particleCount: 4,
                angle: 60,
                spread: 55,
                origin: { x: 0, y: 0.65 }
            });
            confetti({
                particleCount: 4,
                angle: 120,
                spread: 55,
                origin: { x: 1, y: 0.65 }
            });
            if (Date.now() < end) {
                requestAnimationFrame(frame);
            }
        })();

        confetti({
            particleCount: 120,
            spread: 100,
            origin: { y: 0.55 }
        });
    }

    function buildDigitReel(reelEl) {
        reelEl.innerHTML = '';
        const boxes = [];

        for (let i = 0; i < PHONE_DIGIT_COUNT; i++) {
            if (GROUP_GAP_AFTER.includes(i)) {
                const gap = document.createElement('span');
                gap.className = 'lottery-digit-group-gap';
                gap.setAttribute('aria-hidden', 'true');
                reelEl.appendChild(gap);
            }

            const box = document.createElement('div');
            box.className = 'lottery-digit-box';
            box.dataset.index = String(i);
            box.textContent = '–';
            reelEl.appendChild(box);
            boxes.push(box);
        }

        return boxes;
    }

    function displayPhoneInBoxes(boxes, phone, maskMiddle) {
        const digits = normalizePhoneDigits(phone);
        boxes.forEach((box, i) => {
            box.classList.remove('is-masked', 'is-locked', 'is-active', 'is-cycling');
            if (maskMiddle && MASKED_INDICES.includes(i)) {
                box.textContent = '*';
                box.classList.add('is-masked');
            } else {
                box.textContent = digits[i];
            }
        });
    }

    function flashCycle(boxes) {
        boxes.forEach(box => {
            box.classList.add('is-cycling');
        });
        requestAnimationFrame(() => {
            boxes.forEach(box => box.classList.remove('is-cycling'));
        });
    }

    function runLotteryDrawAnimation({ winnerPhone, participantPhones, onComplete, onCancel }) {
        const reelEl = document.getElementById('lottery-digit-reel');
        const statusEl = document.getElementById('lottery-draw-status');
        const countdownEl = document.getElementById('lottery-countdown');
        if (!reelEl) {
            if (typeof onComplete === 'function') onComplete();
            return () => {};
        }

        const phones = extractPhones(participantPhones);
        const winnerDigits = normalizePhoneDigits(winnerPhone);
        const boxes = buildDigitReel(reelEl);

        const cancelRef = {
            cancelled: false,
            timeouts: new Set(),
            intervals: new Set()
        };

        function cancel() {
            if (cancelRef.cancelled) return;
            cancelRef.cancelled = true;
            cancelRef.timeouts.forEach(id => clearTimeout(id));
            cancelRef.intervals.forEach(id => clearInterval(id));
            cancelRef.timeouts.clear();
            cancelRef.intervals.clear();
            if (typeof onCancel === 'function') onCancel();
        }

        function scheduleTimeout(fn, ms) {
            const id = setTimeout(() => {
                cancelRef.timeouts.delete(id);
                fn();
            }, ms);
            cancelRef.timeouts.add(id);
            return id;
        }

        let secondsLeft = COUNTDOWN_START;
        if (countdownEl) countdownEl.textContent = String(secondsLeft);

        const countdownIntervalId = setInterval(() => {
            if (cancelRef.cancelled) return;
            secondsLeft -= 1;
            if (countdownEl) {
                countdownEl.textContent = String(Math.max(0, secondsLeft));
            }
            if (secondsLeft <= 0) {
                clearInterval(countdownIntervalId);
                cancelRef.intervals.delete(countdownIntervalId);
            }
        }, 1000);
        cancelRef.intervals.add(countdownIntervalId);

        function showRandomParticipant() {
            if (cancelRef.cancelled) return;
            const phone = pickRandomPhone(phones) || winnerPhone;
            displayPhoneInBoxes(boxes, phone, false);
            flashCycle(boxes);
            playTickSound();
        }

        showRandomParticipant();

        const cycleIntervalId = setInterval(() => {
            if (cancelRef.cancelled) return;
            showRandomParticipant();
        }, CYCLE_INTERVAL_MS);
        cancelRef.intervals.add(cycleIntervalId);

        scheduleTimeout(() => {
            if (cancelRef.cancelled) return;

            clearInterval(cycleIntervalId);
            cancelRef.intervals.delete(cycleIntervalId);
            clearInterval(countdownIntervalId);
            cancelRef.intervals.delete(countdownIntervalId);

            boxes.forEach((box, i) => {
                box.classList.add('is-locked');
                box.classList.remove('is-cycling', 'is-active');
                if (MASKED_INDICES.includes(i)) {
                    box.textContent = '*';
                    box.classList.add('is-masked');
                } else {
                    box.textContent = winnerDigits[i];
                    box.classList.remove('is-masked');
                }
            });

            if (countdownEl) countdownEl.textContent = '0';
            if (statusEl) statusEl.textContent = 'برنده مشخص شد!';

            fireConfettiBurst();

            if (typeof onComplete === 'function') {
                onComplete();
            }
        }, TOTAL_DURATION_MS);

        return cancel;
    }

    window.runLotteryDrawAnimation = runLotteryDrawAnimation;
    window.normalizePhoneDigits = normalizePhoneDigits;
})();
