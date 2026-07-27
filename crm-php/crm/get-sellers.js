const LOTTERY_PER_PAGE = 20;
let lotteryCurrentPage = 1;

function normalizeCity(city) {
    return (city || '').trim();
}

function getProvinceCityList() {
    return getLotteryFilterCitiesParam();
}


function buildSellersQuery(page, forDraw = false) {
    const params = new URLSearchParams();
    if (forDraw) {
        params.set('for_draw', '1');
    } else {
        params.set('page', String(page));
        params.set('per_page', String(LOTTERY_PER_PAGE));
    }

    const phone = (document.getElementById('search-phone')?.value || '').trim();
    const cities = getProvinceCityList();

    if (phone) params.set('phone', phone);
    if (cities) params.set('cities', cities);

    if (typeof lotteryLog === 'function') {
        lotteryLog(forDraw ? 'build_draw_query' : 'build_list_query', Object.fromEntries(params));
    }

    return params.toString();
}

function updateDrawButtonState(meta, pageRows) {
    const drawBtn = document.getElementById('select-winner-button');
    const emptyMsg = document.getElementById('lottery-participants-empty');
    const countMsg = document.getElementById('lottery-participant-count');
    const totalParticipants = meta?.totalParticipants ?? (pageRows?.length || 0);
    const poolTotalScore = meta?.poolTotalScore ?? 0;
    const hasParticipants = totalParticipants > 0;

    if (drawBtn) {
        drawBtn.disabled = false;
        drawBtn.classList.toggle('lottery-draw-btn-inactive', !hasParticipants);
    }
    if (emptyMsg) {
        emptyMsg.style.display = hasParticipants ? 'none' : 'block';
    }
    if (countMsg) {
        if (hasParticipants) {
            countMsg.textContent = `${totalParticipants} شرکت‌کننده در قرعه‌کشی — مجموع امتیاز: ${poolTotalScore}`;
        } else {
            countMsg.textContent = '';
        }
    }
}

function updatePaginationControls(pagination) {
    const prevBtn = document.getElementById('lottery-prev-page');
    const nextBtn = document.getElementById('lottery-next-page');
    const pageInfo = document.getElementById('lottery-page-info');

    if (!pagination || !pageInfo) return;

    lotteryCurrentPage = pagination.page;
    pageInfo.textContent = `صفحه ${pagination.page} از ${pagination.totalPages} (${pagination.total} نفر)`;

    if (prevBtn) prevBtn.disabled = pagination.page <= 1;
    if (nextBtn) nextBtn.disabled = pagination.page >= pagination.totalPages;
}

function populateSellersTable(sellers, poolTotalScore) {
    const tableBody = document.getElementById('seller-table-body');
    if (!tableBody) return;
    tableBody.innerHTML = '';

    const totalScore = poolTotalScore > 0 ? poolTotalScore : sellers.reduce((sum, s) => sum + s.score, 0);

    sellers.forEach(seller => {
        const tr = document.createElement('tr');
        const chance = totalScore > 0 ? ((seller.score / totalScore) * 100).toFixed(1) : '100.0';
        const province = findProvinceForCity(seller.city);

        tr.innerHTML = `
            <td>${seller.phone}</td>
            <td>${seller.city || '—'}</td>
            <td>${province}</td>
            <td>${seller.serialCount}</td>
            <td>${seller.score}</td>
            <td>${chance}%</td>
        `;

        const actionsTd = document.createElement('td');
        actionsTd.className = 'lottery-admin-action-cell';
        if (typeof createLotteryAdminButtons === 'function') {
            actionsTd.appendChild(createLotteryAdminButtons(seller.phone));
        }
        actionsTd.addEventListener('click', e => e.stopPropagation());

        tr.appendChild(actionsTd);

        tr.addEventListener('click', () => showSellerDetails(seller.phone));
        tableBody.appendChild(tr);
    });
}

function showSellerDetails(phone) {
    fetch(`get-seller-detail.php?phone=${encodeURIComponent(phone)}`)
        .then(res => res.json())
        .then(data => {
            if (!data.success) {
                alert(data.message || 'خطا در دریافت جزئیات');
                return;
            }

            document.getElementById('popup-phone').textContent = data.phone;
            const cityEl = document.getElementById('popup-city');
            if (cityEl) {
                const province = findProvinceForCity(data.city);
                cityEl.textContent = data.city
                    ? `${data.city} (${province !== '—' ? province : 'استان نامشخص'})`
                    : '—';
            }

            const serialsList = document.getElementById('popup-serials');
            serialsList.innerHTML = '';
            data.serials.forEach((serial, index) => {
                const li = document.createElement('li');
                const desc = data.descriptions[index] || 'بدون توضیح';
                li.textContent = `${serial} — ${desc}`;
                serialsList.appendChild(li);
            });

            const descriptionsList = document.getElementById('popup-descriptions');
            descriptionsList.innerHTML = '';
            const uniqueDescriptions = [...new Set((data.descriptions || []).filter(Boolean))];
            if (uniqueDescriptions.length) {
                uniqueDescriptions.forEach(desc => {
                    const li = document.createElement('li');
                    li.textContent = desc;
                    descriptionsList.appendChild(li);
                });
            } else {
                const li = document.createElement('li');
                li.textContent = 'بدون توضیح';
                descriptionsList.appendChild(li);
            }

            document.getElementById('phone-detail-popup').style.display = 'flex';

            const popupResetBtn = document.getElementById('popup-reset-btn');
            const popupSuspendBtn = document.getElementById('popup-suspend-btn');
            if (popupResetBtn) {
                popupResetBtn.onclick = () => confirmAndResetClient(data.phone);
            }
            if (popupSuspendBtn) {
                popupSuspendBtn.onclick = () => confirmAndSuspendClient(data.phone);
            }
        })
        .catch(err => {
            console.error(err);
            alert('خطا در دریافت جزئیات شرکت‌کننده');
        });
}

function fetchSellersData(page = lotteryCurrentPage) {
    const query = buildSellersQuery(page, false);
    if (typeof lotteryLog === 'function') lotteryLog('participants_fetch_request', { url: `get-sellers.php?${query}` });

    return fetch(`get-sellers.php?${query}`)
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                if (typeof lotteryLog === 'function') {
                    lotteryLog('participants_fetch_ok', {
                        page: data.pagination.page,
                        total: data.pagination.total,
                        rows: data.data.length,
                        poolTotalScore: data.meta.poolTotalScore
                    });
                }
                window.lotteryPoolMeta = {
                    totalParticipants: data.pagination.total,
                    poolTotalScore: data.meta.poolTotalScore
                };
                populateSellersTable(data.data, data.meta.poolTotalScore);
                updatePaginationControls(data.pagination);
                updateDrawButtonState({
                    totalParticipants: data.pagination.total,
                    poolTotalScore: data.meta.poolTotalScore
                }, data.data);
                return data.data;
            }

            alert(data.message || 'خطا در بارگذاری شرکت‌کنندگان.');
            if (typeof lotteryLog === 'function') lotteryLog('participants_fetch_fail', { message: data.message });
            updateDrawButtonState({ totalParticipants: 0, poolTotalScore: 0 }, []);
            return [];
        })
        .catch(error => {
            console.error('خطا در دریافت اطلاعات:', error);
            if (typeof lotteryLog === 'function') lotteryLog('participants_fetch_error', { message: error.message });
            alert('خطایی در ارتباط با سرور رخ داد.');
            updateDrawButtonState({ totalParticipants: 0, poolTotalScore: 0 }, []);
            return [];
        });
}

function fetchDrawPool() {
    const query = buildSellersQuery(1, true);
    if (typeof lotteryLog === 'function') lotteryLog('draw_pool_request');
    return fetch(`get-sellers.php?${query}`)
        .then(response => response.json())
        .then(data => {
            if (!data.success || !data.pool?.length) {
                if (typeof lotteryLog === 'function') lotteryLog('draw_pool_empty');
                return null;
            }
            if (typeof lotteryLog === 'function') lotteryLog('draw_pool_ok', { count: data.pool.length });
            return data.pool;
        });
}

function closePopup() {
    document.getElementById('phone-detail-popup').style.display = 'none';
}

function filterTable() {
    lotteryCurrentPage = 1;
    fetchSellersData(1);
}

function lotteryGoToPage(delta) {
    fetchSellersData(lotteryCurrentPage + delta);
}

function selectWinnerFromPool(pool) {
    if (!pool || !pool.length) return null;

    const totalScore = pool.reduce((total, seller) => total + seller.score, 0);
    if (totalScore <= 0) return pool[0];

    let randomNum = Math.random() * totalScore;
    for (const seller of pool) {
        randomNum -= seller.score;
        if (randomNum <= 0) return seller;
    }
    return pool[pool.length - 1];
}

function selectWinner() {
    return fetchDrawPool().then(pool => selectWinnerFromPool(pool));
}

window.fetchDrawPool = fetchDrawPool;
window.selectWinnerFromPool = selectWinnerFromPool;

document.addEventListener('DOMContentLoaded', () => {
    const prevBtn = document.getElementById('lottery-prev-page');
    const nextBtn = document.getElementById('lottery-next-page');

    if (prevBtn) {
        prevBtn.addEventListener('click', () => {
            if (typeof lotteryLog === 'function') lotteryLog('pagination_prev');
            lotteryGoToPage(-1);
        });
    }
    if (nextBtn) {
        nextBtn.addEventListener('click', () => {
            if (typeof lotteryLog === 'function') lotteryLog('pagination_next');
            lotteryGoToPage(1);
        });
    }
});
