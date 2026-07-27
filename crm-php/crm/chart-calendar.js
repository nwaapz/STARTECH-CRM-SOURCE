$(document).ready(function(){
    // Get today's date in Gregorian
    const today = new Date();

    // Convert today's date to Persian (Shamsi) calendar
    const [persianYear, persianMonth] = farvardin.gregorianToSolar(today.getFullYear(), today.getMonth() + 1, today.getDate(), "array");

    // Get first day of the current Persian month
    const firstDayOfMonth = `${persianYear}-${String(persianMonth).padStart(2, '0')}-01`;
    const todayShamsi = farvardin.gregorianToSolar(today.getFullYear(), today.getMonth() + 1, today.getDate(), "string");

    // Set input values
    $("#start_date").val(firstDayOfMonth);
    $("#end_date").val(todayShamsi);

    // Initialize Persian date picker
    $("#start_date, #end_date").persianDatepicker({
        format: "YYYY-MM-DD",
        autoClose: true,
        toolbox: {
            calendarSwitch: { enabled: false } // Prevents unnecessary UI shift
        }
    });
    document.getElementById("start_date").value = toPersianDigits(firstDayOfMonth);
    console.log(firstDayOfMonth);
    // Adjust position manually if needed
    $(".pwt-datepicker").css("top", "40px"); 
});
function toPersianDigits(englishStr) {
    const persianNumbers = ["۰", "۱", "۲", "۳", "۴", "۵", "۶", "۷", "۸", "۹"];
    return englishStr.replace(/\d/g, d => persianNumbers[d]);
}

console.log(toPersianDigits("1404-01-01")); // Output: ۱۴۰۴-۰۱-۰۱
