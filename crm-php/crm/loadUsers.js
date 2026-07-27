let selectedUserId = null; // Store user ID for deletion

// Function to fetch users from the database and display them
function loadUsers() {
    fetch('get_users.php')
        .then(response => response.json())
        .then(users => {
            console.log("Fetched users:", users); // Debugging

            let userList = document.getElementById("user-list");
            userList.innerHTML = ""; // Clear previous list

            users.forEach(user => {
                // Skip users with role 5
                if (Number(user.role) === 5) return;

                console.log("User data:", user); // Debug each user

                let listItem = document.createElement("li");
                listItem.className = "list-group-item d-flex justify-content-between align-items-center";

                let container = document.createElement("div");
                container.className = "d-flex align-items-center gap-3"; // Adds spacing

                let label = document.createElement("span");
                let roleText = "";
                if (Number(user.role) === 1) {
                    roleText = "گزارش‌گیر";
                } else if (Number(user.role) > 1) {
                    roleText = "مدیر";
                }
                label.textContent = user.name + (roleText ? ` (${roleText})` : "");

                label.className = "fw-bold"; // Bold username

                container.appendChild(label); // Always add username

                // Only users with role < 5 get a delete button
                if (Number(user.role) < 5) {
                    let deleteBtn = document.createElement("button");
                    deleteBtn.className = "btn btn-danger btn-sm";
                    deleteBtn.innerHTML = '<i class="fa fa-trash"></i>';
                    deleteBtn.onclick = function () {
                        selectedUserId = user.id;
                        let modal = new bootstrap.Modal(document.getElementById('deleteUserModal'));
                        modal.show();
                    };

                    container.appendChild(deleteBtn); // Append delete button
                }

                listItem.appendChild(container);
                userList.appendChild(listItem);
            });
        })
        .catch(error => console.error('Error fetching users:', error));
}

// Confirm delete user
document.addEventListener("DOMContentLoaded", function () {
    document.getElementById("confirmDeleteUser").addEventListener("click", function () {
        if (!selectedUserId) return;

        fetch('delete_user.php', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: selectedUserId })
        })
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                alert("کاربر با موفقیت حذف شد!");
                loadUsers(); // Refresh user list
            } else {
                alert("خطا در حذف کاربر: " + data.message);
            }
        })
        .catch(error => console.error('Error deleting user:', error));

        let modal = bootstrap.Modal.getInstance(document.getElementById('deleteUserModal'));
        modal.hide();
    });

    // Show user list if role >= 5
    if (typeof userRole !== "undefined" && userRole >= 5) {
        document.getElementById('user-list-section').style.display = 'flex';
        loadUsers(); // Load users on page load
    }
});
