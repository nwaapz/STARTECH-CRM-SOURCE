// Initialize Persian datepickers
$(document).ready(function () {
  $("#time-filter-from").persianDatepicker({
    format: "YYYY-MM-DD",
    autoClose: true,
    toolbox: { calendarSwitch: { enabled: false } },
    onSelect: function () {
      $('#time-filter-to').persianDatepicker('enable');
    }
  });

  $("#time-filter-to").persianDatepicker({
    format: "YYYY-MM-DD",
    autoClose: true,
    toolbox: { calendarSwitch: { enabled: false } },
    onSelect: function () {
      $('#time-filter-from').persianDatepicker('enable');
    }
  });
});

// Hold all fetched inquiries
let allInquiries = [];

// Convert Gregorian "YYYY-MM-DD" → Shamsi string
function convertToShamsi(dateStr) {
  if (!dateStr) return '---';
  const [y, m, d] = dateStr.split("-").map(Number);
  return farvardin.gregorianToSolar(y, m, d, "string");
}

// Convert Shamsi "YYYY-MM-DD" → Gregorian string
function convertToGeorgian(dateStr) {
  if (!dateStr) return '';
  const [y, m, d] = dateStr.split("-").map(Number);
  return farvardin.solarToGregorian(y, m, d, "string");
}

// Convert Persian digits to English
function toEnglishDigits(str) {
  const persian = ["۰","۱","۲","۳","۴","۵","۶","۷","۸","۹"];
  const english = ["0","1","2","3","4","5","6","7","8","9"];
  let out = str;
  persian.forEach((pd, i) => {
    out = out.replace(new RegExp(pd, "g"), english[i]);
  });
  return out;
}

// Single renderTable function matching the <th> order
function renderTable(data) {
  const tbody = document.getElementById("inquiries-table-body");
  tbody.innerHTML = "";
  data.forEach(row => {
    const shamsiDate = convertToShamsi(row.time);
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td>${row.phone  || '---'}</td>
      <td>${row.serial || '---'}</td>
      <td>${row.km     || '---'}</td>
      <td>${row.city   || '---'}</td>
      <td>${shamsiDate}</td>
    `;
    tbody.appendChild(tr);
  });
}


function provinceCityExtract(selectedValue) {
  document.getElementById("city-filter").value = '';
  return extractProvinceCities(selectedValue);
}


// Add event listener to the province dropdown

// Update the HTML to use new function name
document.querySelector('#Ostan').onchange = function() {
  provinceCityExtract(this.value);
};





document.addEventListener("DOMContentLoaded", function () {
  const filterForm      = document.getElementById("filter-form");
  const applyQrBtn      = document.getElementById("apply-filters-qr");
  const timeHeader      = document.getElementById("time-header");
  const timeSortInd     = document.getElementById("time-sort-indicator");

  // Initial empty table
  renderTable([]);

  // Fetch & filter by date range
  filterForm.addEventListener("submit", function (e) {
    e.preventDefault();
    let from = document.getElementById("time-filter-from").value;
    let to   = document.getElementById("time-filter-to").value;

    from = toEnglishDigits(from);
    to   = toEnglishDigits(to);
    from = convertToGeorgian(from);
    to   = convertToGeorgian(to);

    fetch('fetch_inquiries.php', {
      method: 'POST',
      headers: { 'Content-Type':'application/json' },
      body: JSON.stringify({ start_date: from, end_date: to })
    })
    .then(r => r.json())
    .then(data => {
        console.log("fetched",data);
      allInquiries = Array.isArray(data) ? data : [];
      renderTable(allInquiries);
    })
    .catch(err => {
      console.error("Error fetching inquiries:", err);
      alert("خطا در دریافت اطلاعات. لطفاً کنسول را بررسی کنید.");
    });
  });

  // Client-side QR filters: phone, serial, city
  // Inside your DOMContentLoaded (or $(document).ready) where you set up applyQrBtn:
  applyQrBtn.addEventListener("click", function () {
  const phoneFilter = document.getElementById("phone-filter").value.trim();
  const serialFilter = document.getElementById("serial-filter").value.trim();
  const provinceRaw = document.getElementById("Ostan").value;   
  const cityText = document.getElementById("city-filter").value.trim().toLowerCase();
  const citiesArray = provinceCityExtract(provinceRaw);            

  const filtered = allInquiries.filter(row => {
    // 1) phone filter with null check
    if (phoneFilter) {
      if (!row.phone || !row.phone.toString().startsWith(phoneFilter)) {
        return false;
      }
    }

    // 2) serial filter with null check
    if (serialFilter) {
      if (!row.serial || !row.serial.toString().startsWith(serialFilter)) {
        return false;
      }
    }

    // 3) province→city filter: if we have any citiesArray, require row.city ∈ citiesArray
    if (citiesArray.length > 0) {
      if (!row.city || !citiesArray.includes(row.city)) {
        return false;
      }
    }

    // 4) additionally apply city-text startsWith, if provided
    if (cityText) {
      if (!row.city || !row.city.toLowerCase().startsWith(cityText)) {
        return false;
      }
    }

    return true;
  });

  renderTable(filtered);
});

  // Sorting by time column
  let sortOrder = 'desc';
  function updateIndicator() {
    timeSortInd.textContent = sortOrder === 'asc' ? '▲' : '▼';
  }
  updateIndicator();

  timeHeader.addEventListener("click", function () {
    sortOrder = (sortOrder === 'asc') ? 'desc' : 'asc';
    updateIndicator();
    const sorted = allInquiries.slice().sort((a,b) => {
      return sortOrder === 'asc'
        ? a.time.localeCompare(b.time)
        : b.time.localeCompare(a.time);
    });
    renderTable(sorted);
  });
});


$(document).ready(function () {
  // … all your existing setup …

  // Single unified city-input handler:
  $('#city-filter').on('input', function() {
    const txt = $(this).val().trim();

    // 1) Log what's typed
    console.log('City input changed to:', txt);

    if (txt !== '') {
      // 2) Reset province dropdown to its first placeholder option
      $('#Ostan').prop('selectedIndex', 0);
      console.log('Province reset to first option');
    }
  });

  // … the rest of your code …
});



