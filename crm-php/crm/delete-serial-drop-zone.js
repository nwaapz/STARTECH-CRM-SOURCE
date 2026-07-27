document.addEventListener("DOMContentLoaded", function () {
    const dropZoneDelete = document.getElementById("drop-zone-delete");
    const fileInputDelete = document.getElementById("file-input-delete");
    const fileNameDisplayDelete = document.getElementById("file-name-delete");
    const firstColumnDisplayDelete = document.getElementById("first-column-rows-delete");

    // Click to open file dialog
    dropZoneDelete.addEventListener("click", () => fileInputDelete.click());

    // Handle file selection via input
    fileInputDelete.addEventListener("change", (event) => {
        handleFileDelete(event.target.files[0]);
    });

    // Drag and drop functionality
    dropZoneDelete.addEventListener("dragover", (event) => {
        event.preventDefault();
        dropZoneDelete.style.backgroundColor = "#e0e0e0";
    });

    dropZoneDelete.addEventListener("dragleave", () => {
        dropZoneDelete.style.backgroundColor = "#f9f9f9";
    });

    dropZoneDelete.addEventListener("drop", (event) => {
        event.preventDefault();
        dropZoneDelete.style.backgroundColor = "#f9f9f9";

        if (event.dataTransfer.files.length) {
            handleFileDelete(event.dataTransfer.files[0]);
        }
    });

    function handleFileDelete(file) {
        if (!file) return;

        const validExtensions = [".csv", ".xls", ".xlsx"];
        const fileExtension = file.name.substring(file.name.lastIndexOf(".")).toLowerCase();

        if (validExtensions.includes(fileExtension)) {
            fileNameDisplayDelete.textContent = `فایل انتخاب شده: ${file.name}`;
            
            // Read and process the file based on type
            if (fileExtension === ".csv") {
                readCSV(file);
            } else if ([".xls", ".xlsx"].includes(fileExtension)) {
                readExcel(file);
            }
        } else {
            alert("فقط فایل‌های CSV یا Excel مجاز هستند!");
            fileInputDelete.value = ""; // Reset input
        }
    }

    // Function to handle CSV file
    function readCSV(file) {
        const reader = new FileReader();
        
        reader.onload = function (event) {
            const rows = event.target.result.split("\n").map(row => row.split(","));
            const firstColumn = rows
                .map(row => row[0])
                .filter(serial => /\d/.test(serial)); // Only accept rows with at least one digit

            // Show serials in the popup
            showSerialsInPopup(firstColumn);
        };

        reader.readAsText(file);
    }

    // Function to handle Excel file
    function readExcel(file) {
        const reader = new FileReader();
        
        reader.onload = function (event) {
            const data = new Uint8Array(event.target.result);
            const workbook = XLSX.read(data, { type: 'array' });

            // Assuming we read the first sheet
            const sheet = workbook.Sheets[workbook.SheetNames[0]];
            const rows = XLSX.utils.sheet_to_json(sheet, { header: 1 }); // Read rows as an array of arrays

            const firstColumn = rows
                .map(row => row[0])
                .filter(serial => /\d/.test(serial)); // Only accept rows with at least one digit

            // Show serials in the popup
            showSerialsInPopup(firstColumn);
        };

        reader.readAsArrayBuffer(file);
    }

    // Show the serials in the popup
    function showSerialsInPopup(serials) {
        const popup = document.getElementById("popup-delete");
        const serialList = document.getElementById("serial-list-delete");

        // Clear existing serials
        serialList.innerHTML = "";

        // Add serials to the list in the popup
        serials.forEach(serial => {
            let listItem = document.createElement("li");
            listItem.textContent = serial;
            serialList.appendChild(listItem);
        });

        // Show the popup
        popup.style.display = "block";

        // Attach event to the delete button
        const deleteButton = document.getElementById("delete-serials");
        deleteButton.onclick = function () {
            deleteSerialsFromDatabase(serials);
        };
    }

    // Function to delete serials from the database
    function deleteSerialsFromDatabase(serials) {
        // Here you would send the serials to the backend to delete them from the database
        fetch('delete-serials.php', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({ serials: serials }),
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                alert('سریال‌ها با موفقیت حذف شدند!');
            } else {
                alert('خطا در حذف سریال‌ها!');
            }
            closePopup(); // Close the popup after deletion
        })
        .catch(error => {
            console.error('Error deleting serials:', error);
            alert('خطا در ارسال درخواست');
        });
    }

    // Function to close the popup
    function closePopup() {
        const popup = document.getElementById("popup-delete");
        popup.style.display = "none";
    }

    // Close popup on "close" button click
    const closeButton = document.getElementById("close-popup-delete");
    closeButton.addEventListener("click", closePopup);
});
