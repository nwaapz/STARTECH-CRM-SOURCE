document.getElementById("search-btn-new-customer").addEventListener("click", function () {
    let searchType = document.getElementById("search-type").value;
    let searchValue = document.getElementById("search-input").value.trim();

    console.log(searchType);
    console.log(searchValue);
    if (!searchValue) {
        alert("لطفاً مقدار مورد نظر را وارد کنید.");
        return;
    }

    fetch("search_database_new_serial_customer.php", {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
        },
        body: JSON.stringify({ type: searchType, value: searchValue }),
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            document.getElementById("edit-serial-newserials").value = data.serial || "";
            document.getElementById("edit-phone-newserials").value = data.phone || "";
            document.getElementById("edit-km-newserials").value = data.km || "";
            

                        // Convert Gregorian date to Persian (Jalali) date
          

            // Set the input field value to the Persian date
            //document.getElementById("edit-date-newserials").value = persianDate || "";


            document.getElementById("edit-serial-newserials").removeAttribute("readonly");
            document.getElementById("edit-phone-newserials").removeAttribute("readonly");
            document.getElementById("edit-km-newserials").removeAttribute("readonly");

            document.getElementById("edit-day-new-serial").removeAttribute("disabled");
            document.getElementById("edit-year-new-serial").removeAttribute("disabled");
            document.getElementById("edit-month-new-serial").removeAttribute("disabled");
            populateDateDropdowns();

            let persianDate = "-"; // Default if no date is available
            if (data.time) {
                let date = new Date(data.time);

                // Check if it's a valid date
                if (!isNaN(date)) {
                    // Convert Gregorian to Persian (Jalali)
                    let [persianYear, persianMonth, persianDay] = farvardin.gregorianToSolar(date.getFullYear(), date.getMonth() + 1, date.getDate()); // getMonth() is 0-based

                    // Format the Persian date to 'jYYYY/jMM/jDD'
                    persianDate = `${persianYear}/${String(persianMonth).padStart(2, '0')}/${String(persianDay).padStart(2, '0')}`;
                    
                    document.getElementById("edit-year-new-serial").value = persianYear;
                    document.getElementById("edit-month-new-serial").value = persianMonth;
                    document.getElementById("edit-day-new-serial").value = persianDay;
                    console.log(persianYear);
                    
                } else {
                    console.error("Invalid date:", data.time);
                }
            }

            

            function populateDateDropdowns() {
                // انتخاب سال ها از 1400 تا 1407
                let yearSelect = document.getElementById('edit-year-new-serial');
                for (let year = 1400; year <= 1407; year++) {
                    let option = document.createElement('option');
                    option.value = year;
                    option.textContent = year;
                    yearSelect.appendChild(option);
                }
            
                // انتخاب ماه ها از 1 تا 12
                let monthSelect = document.getElementById('edit-month-new-serial');
                for (let month = 1; month <= 12; month++) {
                    let option = document.createElement('option');
                    option.value = month;
                    option.textContent = month;
                    monthSelect.appendChild(option);
                }
            
                // انتخاب روزها از 1 تا 31
                let daySelect = document.getElementById('edit-day-new-serial');
                for (let day = 1; day <= 31; day++) {
                    let option = document.createElement('option');
                    option.value = day;
                    option.textContent = day;
                    daySelect.appendChild(option);
                }
            }
            
            // فراخوانی تابع برای پر کردن دراپ‌دان‌ها
            
            
            // تابع برای نمایش تاریخ انتخابی
            function getSelectedDate() {
                let year = document.getElementById('edit-year-new-serial"').value;
                let month = document.getElementById('edit-month-new-serial"').value;
                let day = document.getElementById('edit-day-new-serial"').value;
            
                if (year && month && day) {
                    // اگر همه مقادیر انتخاب شدند
                    let persianDate = `${year}/${String(month).padStart(2, '0')}/${String(day).padStart(2, '0')}`;
                    console.log("تاریخ انتخابی: ", persianDate);
                    return persianDate;
                } else {
                    console.error("لطفاً همه فیلدها را پر کنید!");
                    return null;
                }
            }









        } else {
            alert("موردی یافت نشد!");
        }
    })
    .catch(error => console.error("خطا در دریافت اطلاعات:", error));
});

// ذخیره تغییرات در دیتابیس
document.getElementById("save-btn-edit-new-serial-client").addEventListener("click", function () {
    return;
    let serial = document.getElementById("edit-serial-newserials").value;
    let phone = document.getElementById("edit-phone-newserials").value;
    let km = document.getElementById("edit-km-newserials").value;



        let day = document.getElementById("edit-day-new-serial").value;
        let month = document.getElementById("edit-month-new-serial").value;
        let year = document.getElementById("edit-year-new-serial").value;

        let date = farvardin.solarToGregorian(year, month, day);

        console.log(date);

    fetch("update_new_serials.php", {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
        },
        body: JSON.stringify({ serial, phone, km, date }),
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert("اطلاعات با موفقیت ذخیره شد!");
        } else {
            alert("خطایی رخ داده است.");
        }
    })
    .catch(error => console.error("خطا در ذخیره اطلاعات:", error));
});
