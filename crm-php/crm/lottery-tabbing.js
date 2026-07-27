document.addEventListener("DOMContentLoaded", function() {
    // Initialize the tabs on page load
    const tabs = document.querySelectorAll("#lottery-tabs .nav-link");
    const tabContents = document.querySelectorAll(".tab-pane");

    // Initially, hide all tab contents
    tabContents.forEach(tabContent => {
        tabContent.classList.remove("show", "active");
    });

    // Show the first tab content by default
    document.querySelector("#file-handler").classList.add("show", "active");

    // Add click event listeners to tabs
    tabs.forEach(tab => {
        tab.addEventListener("click", function(event) {
            event.preventDefault();

            // Remove active classes from all tabs and contents
            tabs.forEach(tab => tab.classList.remove("active"));
            tabContents.forEach(tabContent => tabContent.classList.remove("show", "active"));

            // Add active class to the clicked tab and corresponding content
            tab.classList.add("active");
            const targetContent = document.querySelector(tab.getAttribute("href"));
            targetContent.classList.add("show", "active");
        });
    });
});
