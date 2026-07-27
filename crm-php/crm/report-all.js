document.addEventListener("DOMContentLoaded", function () {
    function fetchTableData() {
        fetch("report-all.php")
            .then(response => response.json())
            .then(data => {
                document.getElementById("total-requests").textContent = data.total_requests;
                document.getElementById("total-rejected").textContent = data.total_rejected;
                document.getElementById("total-confirmed").textContent = data.total_confirmed;
            })
            .catch(error => {
                console.error("خطا در دریافت اطلاعات:", error);
                document.getElementById("total-requests").textContent = "خطا!";
                document.getElementById("total-rejected").textContent = "خطا!";
            });
    }

    fetchTableData(); // دریافت مقدار هنگام بارگذاری صفحه
});
