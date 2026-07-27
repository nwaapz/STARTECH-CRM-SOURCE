document.getElementById('seller-file').addEventListener('change', sellerHandleFileSelect, false);

document.getElementById('mechanic-drop-area').addEventListener('dragover', function(e) {
    e.preventDefault();
    e.stopPropagation();
    this.style.borderColor = '#0d6efd';
}, false);

document.getElementById('mechanic-drop-area').addEventListener('dragleave', function(e) {
    e.preventDefault();
    e.stopPropagation();
    this.style.borderColor = '#adb5bd';
}, false);

document.getElementById('mechanic-drop-area').addEventListener('drop', function(e) {
    e.preventDefault();
    e.stopPropagation();
    this.style.borderColor = '#adb5bd';
    const files = e.dataTransfer.files;
    if (files.length) {
        sellerHandleFileSelect({ target: { files: files } });
    }
}, false);

function sellerHandleFileSelect(event) {
    const file = event.target.files[0];
    if (!file) return;

    const fileName = file.name.toLowerCase();

    if (fileName.endsWith('.xlsx') || fileName.endsWith('.xls')) {
        const reader = new FileReader();
        reader.onload = function(e) {
            const data = e.target.result;
            const workbook = XLSX.read(data, { type: 'binary' });
            const sheet = workbook.Sheets[workbook.SheetNames[0]];
            const rows = XLSX.utils.sheet_to_json(sheet, { header: 1 });

            const validSerials = [];
            rows.forEach(row => {
                const firstColumnValue = row[0];
                if (firstColumnValue && /^[A-Za-z]\d+$/.test(String(firstColumnValue).trim())) {
                    validSerials.push(String(firstColumnValue).trim());
                }
            });

            if (!validSerials.length) {
                alert('هیچ سریال معتبری در فایل یافت نشد.');
                return;
            }

            window.sellerParsedSerials = validSerials;
            sellerShowPopup();
        };
        reader.readAsBinaryString(file);
    } else if (fileName.endsWith('.csv')) {
        const reader = new FileReader();
        reader.onload = function(e) {
            const text = e.target.result;
            const lines = text.split(/\r?\n/);

            const validSerials = [];
            lines.forEach(line => {
                const firstColumnValue = line.split(',')[0].trim();
                if (firstColumnValue && /^[A-Za-z]\d+$/.test(firstColumnValue)) {
                    validSerials.push(firstColumnValue);
                }
            });

            if (!validSerials.length) {
                alert('هیچ سریال معتبری در فایل یافت نشد.');
                return;
            }

            window.sellerParsedSerials = validSerials;
            sellerShowPopup();
        };
        reader.readAsText(file);
    } else {
        alert('لطفاً یک فایل اکسل یا CSV معتبر انتخاب کنید (.xlsx, .xls, .csv).');
    }
}

function sellerShowPopup() {
    document.getElementById('seller-file-description').value = '';
    document.getElementById('seller-info-popup').style.display = 'block';
}

function sellerHidePopup() {
    document.getElementById('seller-info-popup').style.display = 'none';
}

function sellerConfirmPopup() {
    const description = document.getElementById('seller-file-description').value.trim();

    if (!description) {
        alert('لطفاً توضیحات دسته را وارد کنید.');
        return;
    }

    const dataToSend = {
        description: description,
        serials: window.sellerParsedSerials || []
    };

    fetch('insert_sellers.php', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(dataToSend)
    })
    .then(res => res.json())
    .then(response => {
        if (response.success) {
            alert(`✅ ${response.count} سریال با موفقیت ذخیره شد (هر سریال ۱۰ امتیاز).`);
            sellerHidePopup();
            document.getElementById('seller-file').value = '';
            if (typeof fetchSellersData === 'function') {
                fetchSellersData();
            }
        } else {
            alert('❌ خطا در ذخیره اطلاعات: ' + (response.message || ''));
        }
    })
    .catch(err => {
        console.error(err);
        alert('❌ خطایی در ارتباط با سرور رخ داد.');
    });
}

function sellerCancelPopup() {
    sellerHidePopup();
}
