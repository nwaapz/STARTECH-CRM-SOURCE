let targetTable = "";
let serialNumbers = "";
let outputDiv = '';

function toggleProductSection() {
    let section = document.getElementById("product-management-section");
    section.style.display = (section.style.display === "none" || section.style.display === "") 
                              ? "block" 
                              : "none";
}

document.addEventListener("DOMContentLoaded", function() {
    outputDiv = document.getElementById("Sorted-serials-output");
    let dropZone = document.getElementById("drop-zone");
    let fileInput = document.getElementById("file-input");
    let popup = document.getElementById("popup");
    let serialList = document.getElementById("serial-list");
    let saveButton = document.getElementById("save-serials");
    let closeButton = document.getElementById("close-popup");

    // File drop and click functionality
    dropZone.addEventListener("click", function() {
        fileInput.click();
    });

    dropZone.addEventListener("dragover", function(e) {
        e.preventDefault();
        dropZone.style.background = "#e9ecef";
        dropZone.style.borderColor = "#0056b3";
    });

    dropZone.addEventListener("dragleave", function() {
        dropZone.style.background = "#f8f9fa";
        dropZone.style.borderColor = "#007bff";
    });

    dropZone.addEventListener("drop", function(e) {
        e.preventDefault();
        dropZone.style.background = "#f8f9fa";
        dropZone.style.borderColor = "#007bff";
        let files = e.dataTransfer.files;
        if (files.length > 0) {
            handleFileUpload(files[0]);
        }
    });

    fileInput.addEventListener("change", function() {
        if (fileInput.files.length > 0) {
            handleFileUpload(fileInput.files[0]);
        }
    });

    function handleFileUpload(file) {
        if (!file.name.endsWith(".xls") && !file.name.endsWith(".xlsx")) {
            alert("❌ لطفاً یک فایل اکسل معتبر انتخاب کنید.");
            return;
        }
        console.log("Adding serials from file...");
        let reader = new FileReader();
        reader.readAsBinaryString(file);

        reader.onload = function(e) {
            let data = e.target.result;
            let workbook = XLSX.read(data, { type: "binary" });
            let sheetName = workbook.SheetNames[0];
            let sheet = workbook.Sheets[sheetName];
            let jsonData = XLSX.utils.sheet_to_json(sheet, { header: 1 });

            serialNumbers = jsonData
                .map(row => row[0])
                .filter(item => item && /^[A-Za-z]\d{1,10}$/.test(item));

            if (serialNumbers.length === 0) {
                alert("❌ فایل اکسل خالی است یا داده معتبری ندارد.");
                return;
            }
            console.log("File read successfully.");

            // Get selected table from dropdown
            let selectedTable = document.getElementById("add-serial-table-selector").value;
            targetTable = selectedTable;
            console.log("Table selected:", selectedTable);

            // Send table and serial numbers to the server for duplicate checking
            fetch("check_duplicates.php", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ serials: serialNumbers, table: selectedTable })
            })
            .then(response => response.json())
            .then(duplicateSerials => {
                serialList.innerHTML = '';
                if (duplicateSerials.length > 0) {
                    duplicateSerials.forEach(serial => {
                        let listItem = document.createElement("li");
                        listItem.textContent = serial;
                        serialList.appendChild(listItem);
                    });
                    saveButton.innerText = "ذخیره(هشدار سریال تکرار)";
                    document.getElementById("label-in-serial-insert-popup").innerHTML = "کد تکراری وجود دارد";
                    document.getElementById("export-duplicates").style.display = "block";
                } else {
                    serialNumbers.forEach(serial => {
                        let listItem = document.createElement("li");
                        listItem.textContent = serial;
                        serialList.appendChild(listItem);
                    });
                    saveButton.innerText = "ذخیره";
                    document.getElementById("label-in-serial-insert-popup").innerHTML = "اضافه کردن کد های جدید";
                    document.getElementById("export-duplicates").style.display = "none";
                }
                popup.style.display = "flex";
            })
            .catch(error => {
                console.error("❌ Error sending data:", error);
                alert("خطا در بررسی شماره سریال‌ها در دیتابیس.");
            });
        };

        reader.onerror = function(error) {
            console.error("❌ Error reading file:", error);
            alert("خطا در پردازش فایل اکسل!");
        };
    }

    // Close popup
    closeButton.addEventListener("click", function() {
        popup.style.display = "none";
    });

    // Save serials
    saveButton.addEventListener("click", function() {
        insertSerialsToDatabase(serialNumbers);
        popup.style.display = "none";
    });
});

function showTab(tabId) {
    let tabs = document.querySelectorAll('.tab-content');
    tabs.forEach(tab => {
        tab.classList.remove('active');
        tab.style.display = "none";
    });
    let activeTab = document.getElementById(tabId);
    activeTab.classList.add('active');
    activeTab.style.display = "block";
    let tabLinks = document.querySelectorAll('.nav-link');
    tabLinks.forEach(link => link.classList.remove('active'));
    let activeTabLink = document.getElementById(tabId + '-tab');
    activeTabLink.classList.add('active');

    // Process the existing serials tab by fetching grouped data from the server
    if (tabId === "existing-serials") {
        console.log("Tab clicked:", tabId, new Date().toISOString());
        if (window.existingSerialsLoaded) {
            console.log("Serials already loaded, skipping duplicate execution.");
            // Uncomment the next line to prevent duplicate loads if needed:
            // return;
        }
        window.existingSerialsLoaded = true;

        // Fetch grouped serials from the server (SQL handles the grouping)
        
        // Assuming this is in showTab or a similar function
                fetch('get_grouped_serials.php')
                            .then(response => response.json())
                            .then(data => {
                                if (!data.success) {
                                    alert('❌ Server error: ' + (data.message || 'Unknown error'));
                                    return;
                                }

                                console.log("✅ Grouped Serials:", data.groups);
                                // Now you can build the table from data.groups
                                // Each item: { prefix: 'F', start: 'F0001', end: 'F9999', count: 150 }
                            })
                            .catch(error => {
                                console.error('❌ Error fetching grouped serials:', error);
                                alert('Error fetching grouped serials');
                            });
    }
}

function exportDuplicatesToCSV(duplicates) {
    let csvContent = "data:text/csv;charset=utf-8," + "Serial Number\n";
    duplicates.forEach(serial => {
        csvContent += serial + "\n";
    });
    let encodedUri = encodeURI(csvContent);
    let link = document.createElement("a");
    link.setAttribute("href", encodedUri);
    link.setAttribute("download", "duplicates.csv");
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
}

document.getElementById("export-duplicates").addEventListener("click", function () {
    const duplicates = Array.from(document.querySelectorAll("#serial-list li")).map(item => item.textContent);
    exportDuplicatesToCSV(duplicates);
});

function insertSerialsToDatabase(serialNumbers) {
    console.log(targetTable);
    fetch("insert_serials.php", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ serials: serialNumbers, table: targetTable })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert("📤 شماره سریال‌ها با موفقیت در دیتابیس ذخیره شدند!");
        } else {
            alert("❌ خطا در ذخیره‌سازی شماره سریال‌ها: " + (data.message || "خطای نامشخص"));
        }
    })
    .catch(error => {
        console.error("❌ خطا در ارسال داده‌ها:", error);
        alert("خطا در ارسال داده‌ها به سرور.");
    });
}

document.addEventListener("DOMContentLoaded", function() {
    // Get the button element
    let sendConfirmButton = document.getElementById("send-confirm-Sms-to-client");
    let phoneInput = document.getElementById("edit-phone-newserials"); // Assuming this is the input for phone number
    console.log("here");
    // Function to enable the button
    function enableSendButton() {
        sendConfirmButton.disabled = false; // Enable the button
    }

    // Example condition: Enable button if the phone number is entered
    phoneInput.addEventListener("input", function() {
        if (phoneInput.value.trim() !== "") {
            enableSendButton();
        } else {
            sendConfirmButton.disabled = true; // Keep the button disabled if field is empty
        }
    });

    // Add click event listener for the button
    sendConfirmButton.addEventListener("click", function() {
        let phoneNumber = phoneInput.value.trim(); // Get the phone number from the input field
        console.log(phoneNumber);
        if (phoneNumber) {
            // Call the function to send the phone number to the PHP script
            sendPhoneNumberToPhp(phoneNumber);
        } else {
            alert("❌ Please enter a phone number.");
        }
    });

    // Function to send the phone number to the PHP script (smsBridge.php)
    function validatePhoneNumber(phoneNumber) {
    // Example validation: ensure it's numeric and at least 10 digits long
    const phoneRegex = /^[0-9]{10}$/;
    return phoneRegex.test(phoneNumber);
}

function sendPhoneNumberToPhp(phoneNumber) {
    console.log(phoneNumber);

    fetch('smsBridge.php', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({ phoneNumber: phoneNumber })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            console.log("heree");
            alert("پیغام تایید به کاربر ارسال شد ");
            location.reload();
        } else {
            alert("❌ Failed to send SMS: " + (data.message || 'Unknown error'));
        }
    })
    .catch(error => {
        console.error("❌ Error sending phone number:", error);
        alert("خطا در ارسال درخواست. لطفاً دوباره امتحان کنید.");
    });
}
});
