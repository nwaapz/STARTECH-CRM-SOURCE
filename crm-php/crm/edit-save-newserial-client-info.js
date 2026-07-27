document.getElementById("save-btn-edit-new-serial-client").addEventListener("click", function () {
    let serial = document.getElementById("edit-serial-newserials").value.trim();
    let phone = document.getElementById("edit-phone-newserials").value.trim();
    let km = document.getElementById("edit-km-newserials").value.trim();

    // Convert inputs to numbers using parseInt (or Number())
    let year  = parseInt(document.getElementById("edit-year-new-serial").value, 10);
    let month = parseInt(document.getElementById("edit-month-new-serial").value, 10);
    let day   = parseInt(document.getElementById("edit-day-new-serial").value, 10);

    console.log("Year:", year);   // e.g., 1400
    console.log("Month:", month); // e.g., 7
    console.log("Day:", day);     // e.g., 14

    // Call the conversion function with numeric values
    let date1 = farvardin.solarToGregorian(year, month, day, "string");
    console.log("Converted Gregorian Date:", date1);


    if (!serial || !phone) {
        alert("شماره سریال و شماره تلفن الزامی هستند.");
        return;
    }

    let data = {
        serial: serial,
        phone: phone,
        km: km,
        date: date1
    };

    fetch("update_new_serials.php", {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify(data)
    })
    .then(response => response.json())
    .then(result => {
        if (result.success) {
            alert("اطلاعات با موفقیت ذخیره شد!");
            document.getElementById("send-confirm-Sms-to-client").removeAttribute("disabled");
        } else {
            alert("خطا در ذخیره اطلاعات: " + result.message);
        }
    })
    .catch(error => {
        console.error("خطا در ارسال درخواست:", error);
        alert("خطا در ارتباط با سرور!");
    });
});
document.querySelectorAll(".editClientTextBoxes").forEach(textbox => {
    textbox.addEventListener("keydown", function() {
        if(!document.getElementById("edit-serial-newserials").hasAttribute("readonly"))
        {
            document.getElementById("save-btn-edit-new-serial-client").disabled = false;
        }
   
    });
});

document.querySelectorAll(".editClientDrop").forEach(textbox => {
    textbox.addEventListener("change", function() {
        if(!document.getElementById("edit-serial-newserials").hasAttribute("readonly"))
        {
            document.getElementById("save-btn-edit-new-serial-client").disabled = false;
        }
   
    });
});
