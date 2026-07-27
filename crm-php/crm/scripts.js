// تابعی برای پر کردن دراپ‌دان ها


// به عنوان مثال: هنگام کلیک بر روی دکمه یا هر عملی دیگر



document.addEventListener("DOMContentLoaded", function () {
    loadPage('dashboard');
    showSmsTab('sms-winner');
    initializeSaveButton(); // Ensure the save button is initially disabled
});

function loadPage(page) {
        document.querySelectorAll("main > div").forEach(section => section.style.display = "none");
        const selectedSection = document.getElementById(page);
        if (selectedSection) {
            selectedSection.style.display = "block";
        }

        if (page === 'lottery') {
            const bootLottery = () => {
                if (typeof lotteryLog === 'function') lotteryLog('page_open', { page: 'lottery' });
                if (typeof initLotteryUi === 'function') initLotteryUi();
                if (typeof loadWinnersTable === 'function') {
                    if (typeof lotteryLog === 'function') lotteryLog('history_fetch_start');
                    loadWinnersTable();
                }
                if (typeof fetchSellersData === 'function') {
                    if (typeof lotteryLog === 'function') lotteryLog('participants_fetch_start');
                    fetchSellersData(1);
                }
            };
            bootLottery();
        }

        if (page === 'admins') {
            if (typeof initTestPhonesManager === 'function') initTestPhonesManager();
            if (typeof loadTestPhones === 'function') loadTestPhones();
        }

        if (page === 'sms-settings') {
            showSmsTab('sms-winner');
        }

        if (page === 'image-verification') {
            if (typeof initImageVerificationSettings === 'function') initImageVerificationSettings();
            if (typeof initImageVerification === 'function') initImageVerification();
        }
}

function showSmsTab(tab) {
    const smsWinner = document.getElementById("sms-winner");
    const smsOld = document.getElementById("sms-old");
    const smsNew = document.getElementById("sms-new");

    if (smsWinner) smsWinner.style.display = "none";
    if (smsOld) smsOld.style.display = "none";
    if (smsNew) smsNew.style.display = "none";

    const selectedTab = document.getElementById(tab);
    if (selectedTab) {
        selectedTab.style.display = "block";
    }

    if (tab === 'sms-winner') {
        if (typeof initWinnerSmsSettings === 'function') {
            initWinnerSmsSettings();
        }
    } else if (tab === 'sms-old') {
        fetchSmsMessages();
    } else if (tab === 'sms-new') {
        addDashedBoxForNewSystem();
    }
}

async function saveOldSystemData() {
    const inputs = document.querySelectorAll("#ad-message-fields input");
    const messages = Array.from(inputs).map(input => input.value.trim()).filter(value => value);

    const jsonData = JSON.stringify({ messages });

    try {
        const response = await fetch('save_sms_messages.php', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: jsonData
        });

        const text = await response.text();
        console.log("📥 Raw Response text:", text);

        try {
            const data = JSON.parse(text);
            console.log("✅ Parsed JSON:", data);
            alert(data.success ? 'پیام‌ها با موفقیت ذخیره شدند!' : `خطا: ${data.message || 'نامشخص'}`);
        } catch (error) {
            console.error("❌ JSON Parse Error:", error);
            alert('پاسخ سرور معتبر نیست! لطفاً کنسول را بررسی کنید.');
        }
    } catch (error) {
        console.error('❌ Error saving messages:', error);
        alert('خطا در ارسال درخواست ذخیره');
    }
}

async function deleteWelcomeMessages() {
    try {
        const response = await fetch('save_sms_messages.php', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ messages: [] })
        });

        const data = await response.json();
        if (data.success) {
            alert('پیام‌های تبلیغاتی با موفقیت حذف شدند!');
            document.getElementById("ad-message-count").value = 0;
            document.getElementById("ad-message-fields").innerHTML = '';
        } else {
            alert(`خطا در حذف پیام‌ها: ${data.message}`);
        }
    } catch (error) {
        console.error('❌ Error deleting messages:', error);
        alert('خطا در ارسال درخواست حذف');
    }
}

async function fetchSmsMessages() {
    try {
        const response = await fetch('get_sms_messages.php');
        const data = await response.json();
        initializeOldSmsFields(data);
    } catch (error) {
        console.error('❌ Error fetching messages:', error);
    }
}

function initializeOldSmsFields(data) {
    const { messages, count } = data;
    const dropdown = document.getElementById("ad-message-count");
    
    dropdown.innerHTML = "";
    for (let i = 0; i <= 5; i++) {
        let option = new Option(`تعداد پیام‌ها: ${i}`, i);
        dropdown.appendChild(option);
    }

    dropdown.value = count > 5 ? 5 : count;
    dropdown.addEventListener('change', () => updateMessageFields(parseInt(dropdown.value), messages));

    updateMessageFields(parseInt(dropdown.value), messages);
}

function updateMessageFields(count, messages) {
    let container = document.getElementById("ad-message-fields");
    container.innerHTML = ""; // پاک کردن محتوای قبلی

    for (let i = 0; i < count; i++) {
        let input = document.createElement("input");  // استفاده از input
        input.type = "text";
        input.className = "add_message";  // کلاس جدید
        input.value = messages[i] || "";  // مقداردهی اولیه
        input.style.marginBottom = "15px";  // اضافه کردن فاصله
        input.addEventListener('input', handleInputChange);  // اضافه کردن event listener برای تغییرات تکس باکس
        container.appendChild(input);
    }
}

// Initialize the save button state (disabled by default)
function initializeSaveButton() {
    const saveButton = document.getElementById('save-button');
    saveButton.disabled = true;  // Start with the button disabled
    saveButton.classList.add('btn-secondary');  // Apply disabled style (gray color)
}

// Function to enable save button
function enableSaveButton() {
    const saveButton = document.getElementById('save-button');
    saveButton.disabled = false;  // Enable the button
    saveButton.classList.remove('btn-secondary');  // Remove the disabled class
    saveButton.classList.add('btn-success');  // Add the active (green) class
}

// Handle input change in textboxes
function handleInputChange() {
    enableSaveButton();  // Enable the save button whenever there's a change in the textboxes
}

// Check for changes in the dropdown
document.getElementById('ad-message-count').addEventListener('change', function() {
    enableSaveButton();  // Enable the save button whenever there's a change in the dropdown
});

// Adding event listener for dynamically created textboxes using event delegation
document.getElementById('ad-message-fields').addEventListener('input', function(event) {
    if (event.target && event.target.classList.contains('add_message')) {
        enableSaveButton();  // Enable the save button whenever there's a change in the textboxes
    }
});

document.addEventListener("DOMContentLoaded", function () {
    fetchDatabaseData(); // Fetch data when page loads
});

async function fetchDatabaseData() {
    try {
        const response = await fetch('get_sms_data.php');
        const data = await response.json();

        if (data.success) {
            document.getElementById("message-a").value = data.data.a || "";
            document.getElementById("message-b").value = data.data.b || "";
            document.getElementById("message-c").value = data.data.c || "";
        } else {
            console.error("❌ Error: " + data.message);
        }
    } catch (error) {
        console.error("❌ Fetch Error:", error);
    }
}


