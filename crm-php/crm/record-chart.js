document.addEventListener("DOMContentLoaded", function () {
    



    
    
    
    let chartInstance;

    function fetchRequestsByDate(startDate, endDate) {
        console.log("📅 Fetching Data for Range:", startDate, "to", endDate);

        const ctx = document.getElementById("requestsChart")?.getContext("2d");
        if (!ctx) {
            console.error("❌ Canvas not found or context not available");
            return;
        }

        fetch(`record-chart.php?start_date=${startDate}&end_date=${endDate}`)
            .then(response => response.json())
            .then(data => {
                if (!Array.isArray(data)) {
                    console.error("❌ Invalid data format:", data);
                    return;
                }

                const labels = data.map(item => {
                    const [year, month, day] = item.request_date.split('-').map(Number);
                    return farvardin.gregorianToSolar(year, month, day, "string");
                });

                const counts = data.map(item => item.count);

                if (chartInstance) chartInstance.destroy();

                ctx.canvas.width = ctx.canvas.parentElement.clientWidth;
                ctx.canvas.height = ctx.canvas.parentElement.clientHeight;

                chartInstance = new Chart(ctx, {
                    type: 'bar',
                    data: {
                        labels: labels,
                        datasets: [{
                            label: 'تعداد تایید شده',
                            data: counts,
                            backgroundColor: 'rgba(54, 162, 235, 0.5)',
                            borderColor: 'rgba(54, 162, 235, 1)',
                            borderWidth: 1
                        }]
                    },
                    options: {
                        scales: {
                            y: {
                                beginAtZero: true,
                                ticks: {
                                    stepSize: 1,
                                    callback: value => (Number.isInteger(value) ? value : '')
                                }
                            }
                        },
                        plugins: {
                            legend: { display: false }
                        }
                    }
                });
            })
            .catch(error => console.error("خطا در دریافت داده‌های نمودار:", error));
    }

    function persianToEnglish(persianStr) {
        const persianNumbers = ["۰", "۱", "۲", "۳", "۴", "۵", "۶", "۷", "۸", "۹"];
        const englishNumbers = ["0", "1", "2", "3", "4", "5", "6", "7", "8", "9"];
        return persianNumbers.reduce((result, persian, index) => result.replace(new RegExp(persian, "g"), englishNumbers[index]), persianStr);
    }

    // Set Persian Start Date Correctly
    const today = new Date();
    const [persianYear, persianMonth] = farvardin.gregorianToSolar(today.getFullYear(), today.getMonth() + 1, today.getDate(), "array");
    const startOfMonth = `${persianYear}-${String(persianMonth).padStart(2, '0')}-01`;

    document.getElementById("start_date").value = startOfMonth;

    // If needed, log to check the value
    console.log("Start date (First day of current Persian month):", startOfMonth);
    console.log( document.getElementById("start_date").value);

    // Convert Start of Month to Gregorian
    const startGregorian = farvardin.solarToGregorian(persianYear, persianMonth, 1, "string");
    const todayGregorian = today.toISOString().split("T")[0];

    // Set Default Input Values in Persian
    //document.getElementById("start_date").value = startOfMonth;
    document.getElementById("end_date").value = farvardin.gregorianToSolar(today.getFullYear(), today.getMonth() + 1, today.getDate(), "string");

    fetchRequestsByDate(startGregorian, todayGregorian);

    function updateChartIfDatesSelected() {
        const startInput = persianToEnglish(document.getElementById("start_date").value);
        const endInput = persianToEnglish(document.getElementById("end_date").value);

        if (startInput && endInput) {
            try {
                const [startYear, startMonth, startDay] = startInput.split('-').map(Number);
                const [endYear, endMonth, endDay] = endInput.split('-').map(Number);

                const startGregorian = farvardin.solarToGregorian(startYear, startMonth, startDay, "string");
                const endGregorian = farvardin.solarToGregorian(endYear, endMonth, endDay, "string");

                fetchRequestsByDate(startGregorian, endGregorian);
            } catch (error) {
                console.error("⚠️ Persian date conversion failed!", error);
            }
        }
    }

   // document.getElementById("start_date").addEventListener("change", updateChartIfDatesSelected);
   // document.getElementById("end_date").addEventListener("change", updateChartIfDatesSelected);
    document.getElementById("filterChart").addEventListener("click", updateChartIfDatesSelected);
});
