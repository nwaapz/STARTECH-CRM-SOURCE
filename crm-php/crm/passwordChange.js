document.addEventListener('DOMContentLoaded', function() {
    const form = document.getElementById('password-change-form');
    if (!form) return; // Exit if the form isn't present

    form.addEventListener('submit', function(event) {
        event.preventDefault();
        
        const newPassword = document.getElementById('new-password').value;
        const confirmPassword = document.getElementById('confirm-password').value;
        
        // Check if the passwords match
        if (newPassword !== confirmPassword) {
            alert('رمز عبور و تایید آن یکسان نیستند.');
            return;
        }
        
        // Assuming the current user's name is stored in a JS variable called "userRole"
        // If userRole represents the username, otherwise adjust accordingly.
        const username = userName; 
        console.log(userRole+"role");
        // Prepare data to be sent to the backend
        const payload = {
            username: username,
            newPassword: newPassword
        };

        // Send the new password to the server to update in the database
        fetch('change_password.php', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
        .then(response => response.json())
        .then(result => {
            if (result.success) {
                alert('رمز عبور با موفقیت تغییر یافت.');
                form.reset();
            } else {
                alert('خطا: ' + result.message);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert('خطایی در تغییر رمز عبور رخ داد.');
        });
    });
});
