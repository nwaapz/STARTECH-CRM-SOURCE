// Shared province/city helpers — same data model as #Ostan (value = comma-separated cities)

function extractProvinceCities(ostanValue) {
    const cleanedString = (ostanValue || '').trim().replace(/^,/, '');
    return cleanedString.split(',')
        .map(city => city.trim())
        .filter(city => city !== '');
}

function findProvinceForCity(city) {
    const normalized = (city || '').trim();
    if (!normalized) return '—';

    const sources = [
        document.getElementById('Ostan'),
        document.getElementById('lottery-province')
    ];

    for (const select of sources) {
        if (!select) continue;
        for (const opt of select.options) {
            if (!opt.value) continue;
            const cities = extractProvinceCities(opt.value);
            if (cities.includes(normalized)) {
                return opt.textContent.trim();
            }
        }
    }
    return '—';
}

function getLotteryFilterCitiesParam() {
    const cityValue = document.getElementById('lottery-city')?.value || '';
    if (cityValue) return cityValue;

    const provSelect = document.getElementById('lottery-province');
    if (!provSelect || provSelect.selectedIndex < 0) return '';

    const opt = provSelect.options[provSelect.selectedIndex];
    if (!opt || !opt.value) return '';

    return extractProvinceCities(opt.value).join(',');
}
