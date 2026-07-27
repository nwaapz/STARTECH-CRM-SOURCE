document.addEventListener("DOMContentLoaded", function () {
    // Initialize the save button state
    initializeSaveButtonMain();
    
    // Add event listener for the save button
    document.getElementById('save-button-main').addEventListener('click', saveMainSmsData);
});

function initializeSaveButtonMain() {
    const saveButton = document.getElementById('save-button-main');
    saveButton.disabled = true; // Disable the button initially
    saveButton.classList.add('btn-secondary'); // Add disabled style
}

// Function to enable the save button when the input fields are changed
function enableSaveButtonMain() {
    const saveButton = document.getElementById('save-button-main');
    saveButton.disabled = false; // Enable the button
    saveButton.classList.remove('btn-secondary'); // Remove disabled style
    saveButton.classList.add('btn-success'); // Add success style
}

// Save the data from message-a, message-b, message-c to the database
async function saveMainSmsData() {
    const messageA = document.getElementById("message-a").value.trim();
    const messageB = document.getElementById("message-b").value.trim();
    const messageC = document.getElementById("message-c").value.trim();

    // Prepare the data to be sent to the server
    const data = {
        a: messageA,
        b: messageB,
        c: messageC
    };

    try {
        const response = await fetch('save_main_sms.php', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(data)
        });

        const result = await response.json();

        if (result.success) {
            alert('پیام‌ها با موفقیت ذخیره شدند!');
        } else {
            alert('خطا در ذخیره‌سازی: ' + result.message);
        }
    } catch (error) {
        console.error('❌ Error saving messages:', error);
        alert('خطا در ارسال درخواست ذخیره');
    }
}

// Event listener for input changes to enable the save button
document.querySelectorAll('.main_message').forEach(input => {
    input.addEventListener('input', enableSaveButtonMain);
});
