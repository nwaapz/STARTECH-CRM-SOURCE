window.LOTTERY_DEBUG = true;
window.lotteryTraceLog = [];

function lotteryLog(step, detail) {
    const entry = {
        time: new Date().toLocaleTimeString('fa-IR'),
        step,
        detail: detail ?? null
    };
    window.lotteryTraceLog.push(entry);

    if (window.LOTTERY_DEBUG) {
        console.log('[Lottery]', step, detail ?? '');
    }

    const pre = document.getElementById('lottery-trace-log');
    if (pre) {
        pre.textContent = window.lotteryTraceLog
            .map(e => {
                const d = e.detail == null ? '' : (typeof e.detail === 'object' ? JSON.stringify(e.detail) : String(e.detail));
                return `${e.time} | ${e.step}${d ? ' | ' + d : ''}`;
            })
            .join('\n');
    }
}

function populateLotteryProvincesFromOstan() {
    const ostan = document.getElementById('Ostan');
    const lotteryProvince = document.getElementById('lottery-province');

    if (!ostan) {
        lotteryLog('ostan_source_missing');
        return false;
    }
    if (!lotteryProvince) {
        lotteryLog('province_select_missing');
        return false;
    }

    while (lotteryProvince.options.length > 1) {
        lotteryProvince.remove(1);
    }

    let count = 0;
    Array.from(ostan.options).forEach(opt => {
        if (!opt.value && !opt.textContent.trim()) return;
        if (!opt.value) return;
        lotteryProvince.add(new Option(opt.textContent.trim(), opt.value));
        count++;
    });

    lotteryLog('provinces_injected_from_ostan', { count });
    return count > 0;
}

function populateLotteryCitiesFromProvinceOption(provinceOptionValue) {
    const select = document.getElementById('lottery-city');
    if (!select) {
        lotteryLog('city_select_missing');
        return false;
    }

    select.innerHTML = '<option value="">-- تمام شهرها --</option>';

    if (!provinceOptionValue) {
        lotteryLog('cities_cleared');
        return true;
    }

    const cities = extractProvinceCities(provinceOptionValue);
    cities.forEach(city => select.add(new Option(city, city)));

    lotteryLog('cities_injected', { count: cities.length });
    return true;
}

function bindLotteryFilterEvents() {
    const provinceSelect = document.getElementById('lottery-province');
    const citySelect = document.getElementById('lottery-city');

    if (provinceSelect && !provinceSelect.dataset.lotteryBound) {
        provinceSelect.dataset.lotteryBound = '1';
        provinceSelect.addEventListener('change', () => {
            const opt = provinceSelect.options[provinceSelect.selectedIndex];
            lotteryLog('province_changed', { name: opt?.textContent?.trim(), valueLength: opt?.value?.length ?? 0 });
            populateLotteryCitiesFromProvinceOption(opt?.value || '');
            if (typeof filterTable === 'function') filterTable();
        });
    }

    if (citySelect && !citySelect.dataset.lotteryBound) {
        citySelect.dataset.lotteryBound = '1';
        citySelect.addEventListener('change', () => {
            lotteryLog('city_changed', { value: citySelect.value });
            if (typeof filterTable === 'function') filterTable();
        });
    }

    lotteryLog('filter_events_bound');
}

function initLotteryUi() {
    lotteryLog('ui_init_start');

    const checks = {
        lotteryPage: !!document.getElementById('lottery'),
        ostanSource: !!document.getElementById('Ostan'),
        provinceSelect: !!document.getElementById('lottery-province'),
        citySelect: !!document.getElementById('lottery-city'),
        tableBody: !!document.getElementById('seller-table-body'),
        drawButton: !!document.getElementById('select-winner-button'),
        historyBody: !!document.getElementById('lottery-history-body'),
        pagination: !!document.getElementById('lottery-pagination')
    };
    lotteryLog('dom_check', checks);

    const ok = populateLotteryProvincesFromOstan();
    if (ok) {
        bindLotteryFilterEvents();
    }

    lotteryLog('ui_init_done', { ostanReady: ok });
    return ok;
}

window.initLotteryUi = initLotteryUi;
window.lotteryLog = lotteryLog;
