document.addEventListener('DOMContentLoaded', function () {
    fetch('get_grouped_serials.php')
        .then(response => response.json())
        .then(data => {
            const container = document.getElementById('Sorted-serials-output');
            container.innerHTML = ''; // clear previous content

            if (data.success && data.groups.length > 0) {
                data.groups.forEach(group => {
                    const btn = document.createElement('button');
                    btn.className = 'btn btn-outline-primary m-2';
                    btn.innerHTML = `گروه ${group.prefix} (${group.count} سریال)`;
                    // Don't add any click event here!
                    container.appendChild(btn);
                });
            } else {
                container.innerHTML = '<p>داده‌ای برای نمایش وجود ندارد.</p>';
            }
        })
        .catch(error => {
            console.error('Error fetching grouped serials:', error);
        });
});
