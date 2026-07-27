// تابعی برای اضافه کردن کادر نقطه چین به سیستم جدید
function addDashedBoxForNewSystem() {
    console.log("hhhh");
    // انتخاب قسمت container که می‌خواهید کادر جدید را داخل آن قرار دهید
    let container = document.getElementById("new-system-container");
    let textfield = document.getElementById("newSmsInpuId");
    if(textfield) return;
    // ایجاد div جدید برای کادر نقطه چین
    let dashedBoxDiv = document.createElement("div");
    dashedBoxDiv.classList.add("dashed-box");  // اضافه کردن کلاس نقطه چین

    // افزودن یک input یا text area برای وارد کردن محتوا
    let input = document.createElement("textarea");
    input.className = "form-control";  // استایل پایه Bootstrap
    input.id = "newSmsInpuId";
    dashedBoxDiv.appendChild(input);  // اضافه کردن text area به کادر نقطه چین
    
    let saveButton = document.createElement("button");
    saveButton.id = "new_main_sms_save";  // Set the specified button ID
    saveButton.className = "btn btn-success mt-3";  // Bootstrap styling for the button
    saveButton.disabled = true;  // Disable the button initially
    saveButton.textContent = "ذخیره";
    
    dashedBoxDiv.appendChild(saveButton);

    container.appendChild(dashedBoxDiv);
    // افزودن کادر نقطه چین به container
    saveButton.addEventListener("click", function() {
        saveNewMessage(input.value);  // Call your function with the input value
    });
    NewSms_fetchDatabaseData(saveButton);
}

// Function to save the new message to the database
function saveNewMessage() {
    const message = document.getElementById('newSmsInpuId').value.trim();  // Get the message text
    if (!message) {
        alert('Please enter a message!');
        return;
    }

    // Prepare the data to send to the server
    const data = {
        message: message
    };

    // Send the message to the server using fetch
    fetch('new_save_sms_message.php', {  // Updated to point to the new PHP file
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(data)
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert('Message saved successfully!');
        } else {
            alert('Error saving message: ' + (data.message || 'Unknown error'));
        }
    })
    .catch(error => {
        console.error('Error saving message:', error);
        alert('Error saving message!');
    });
}

async function NewSms_fetchDatabaseData(input) {
    try {
        const response = await fetch('get_new_sms_content.php');
        const data = await response.json();
        console.log(data.data);
        
        if (data.success) {
            document.getElementById("newSmsInpuId").value = data.message || "";
            input.addEventListener("input", function() {
                saveButton.disabled = input.value.trim() === "";  // Enable the button if the textarea is not empty
            });
            
        } else {
            console.error("❌ Error: " + data.message);
        }
    } catch (error) {
        console.error("❌ Fetch Error:", error);
    }
}

document.addEventListener("DOMContentLoaded", function() {
    document.addEventListener("keydown", function(event) {
        if (document.activeElement.id === "newSmsInpuId" ) {
            let new_sms_save_btn = document.getElementById("new_main_sms_save");
            new_sms_save_btn.disabled = false;
        }
    });
});
