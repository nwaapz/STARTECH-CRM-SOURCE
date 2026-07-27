<?php
session_start();

// Check if the user is logged in
if (!isset($_SESSION['loggedin']) || $_SESSION['loggedin'] !== true) {
    header('Location: login.html'); // or your login page
    exit;
}

$role = $_SESSION['role'];

// Define timeout duration (8 hours)
$timeout_duration = 8 * 3600; // 8 hours in seconds

// Check if login_time is set and if the session has expired
if (isset($_SESSION['login_time']) && (time() - $_SESSION['login_time']) > $timeout_duration) {
    // Unset all session variables
    session_unset();
    // Destroy the session
    session_destroy();
    // Redirect to login page
    header('Location: login.html');
    exit;
}

// Optionally, update the login time on activity if you want to reset the timeout on each request
// $_SESSION['login_time'] = time();
?>



<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        /* Apply Arabic font and Arabic-Indic numbers */
        
    </style>
    <title>پنل مدیریتی دکتر تک</title>
    <!-- Use this instead in your head section -->
    <script src="https://unpkg.com/iran-cities@latest/dist/iran-cities.min.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/jspdf/2.5.1/jspdf.umd.min.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/jspdf-autotable/3.5.30/jspdf.plugin.autotable.min.js"></script>

    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/persian-datepicker@1.2.0/dist/css/persian-datepicker.min.css">
    <script src="https://cdnjs.cloudflare.com/ajax/libs/dayjs/1.11.10/dayjs.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/@arashk/dayjs-jalali@latest/dist/jalali.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
<!-- Add Bootstrap JS (for modals, tooltips, etc.) -->
<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js"></script>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.rtl.min.css" rel="stylesheet">
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0-beta3/css/all.min.css">
    

    <!-- لینک به فایل CSS جداگانه -->
    <link rel="stylesheet" href="styles.css?v=5">
    <link rel="stylesheet" href="product.css">
</head>
<body>
<h6>هم اکنون در پنل: <?php echo htmlspecialchars($_SESSION['username']); ?></h6>
     <!-- پاپ‌آپ سریال‌ها -->
     <script>  var userName = <?php echo json_encode($_SESSION['username']); ?>; 
      console.log(userName);
     
     </script> 
    <script>  var userRole = <?php echo json_encode($role); ?>;   </script> 
    <div class="header">پنل مدیریتی دکتر تک</div>
    <div class="navbar">

        <script>
            // Pass the PHP role to a JavaScript variable
        var userRole = <?php echo json_encode($role); ?>;
        </script>



        <a href="javascript:void(0)" onclick="checkRoleAndLoadPage('sms-settings')">
        <i class="fas fa-comment-alt"></i> تنظیمات پیامک و پاسخ‌ها
        </a>
        <a href="javascript:void(0)" onclick="loadPage('dashboard')">
            <i class="fas fa-tachometer-alt"></i> داشبورد کلی
        </a>
        <a href="javascript:void(0)" onclick="loadPage('product'); checkRoleAndToggle()">
            <i class="fas fa-box-open"></i> مدیریت محصولات
        </a>
        <a href="javascript:void(0)" onclick="loadPage('inquiries')">
            <i class="fas fa-search"></i> گزارش‌گیری و تحلیل  استعلام‌ها
        </a>
        <a href="javascript:void(0)" onclick="checkRoleAndLoadPage('admins')">
            <i class="fas fa-users-cog"></i> مدیریت ادمین‌ها
        </a>
        <a href="javascript:void(0)" onclick="checkRoleAndLoadPage('lottery')">
            <i class="fas fa-gift"></i> سیستم قرعه‌کشی فروشندگان
        </a>
        <a href="javascript:void(0)" onclick="checkRoleAndLoadPage('image-verification')">
            <i class="fas fa-camera"></i> تأیید تصویر
        </a>



        <script>
                    // Function to check the user role before executing the page load
                    function checkRoleAndLoadPage(page) {
                        if (userRole >=3 ) {
                            loadPage(page); // If role is 1, load the page
                        } else {
                            alert("دسترسی محدود است. تنها مدیران می‌توانند این بخش را مشاهده کنند.");
                        }
                    }
                    function checkRoleAndToggle() {
                        if (userRole >=3 ) {
                            toggleProductSection(); // If role is 1, load the page
                        } else {
                            alert("دسترسی محدود است. تنها مدیران می‌توانند این بخش را مشاهده کنند.");
                        }
                    }
                  
        </script>



    </div>
    <div class="container-fluid">
        <main id="main-content" class="px-md-4 py-4">
            <div id="sms-settings" class="page" style="display: none;">
                <h1>تنظیمات پیامک و پاسخ‌ها</h1>
                <div class="tab-container">
                    <div class="tab-buttons">
                        <button type="button" onclick="showSmsTab('sms-winner')">پیامک برنده قرعه‌کشی</button>
                        <button type="button" onclick="showSmsTab('sms-old')">سیستم قدیمی</button>
                        <button type="button" onclick="showSmsTab('sms-new')">سیستم جدید</button>
                    </div>
                    <div id="sms-winner" style="display: none;">
                        <h2>پیامک اطلاع‌رسانی برنده قرعه‌کشی</h2>
                        <div class="dashed-box mt-3">
                            <p class="small text-muted" style="width: 90%; margin: 0 auto 12px;">این پیام پس از تأیید برنده در قرعه‌کشی برای شماره برنده ارسال می‌شود.</p>
                            <div class="input-group mb-3 d-flex justify-content-center">
                                <label for="winner-sms-message" class="input-label" style="font-weight: bold; color: #bbb;">متن پیامک برنده</label>
                                <textarea id="winner-sms-message" class="form-control" rows="5" style="margin-bottom: 15px; width: 90%;"></textarea>
                            </div>
                            <div class="d-flex flex-wrap gap-2 justify-content-center">
                                <button type="button" id="winner-sms-save-btn" class="btn btn-success mt-1" disabled>ذخیره</button>
                                <button type="button" id="winner-sms-test-btn" class="btn btn-outline-primary mt-1"<?php echo ((int) $role >= 5) ? '' : ' style="display: none;"'; ?>>ارسال آزمایشی به شماره‌های تست</button>
                            </div>
                            <p id="winner-sms-status" class="small text-muted text-center mt-2 mb-0"></p>
                        </div>
                    </div>
                    <div id="sms-old" style="display: none;">
                        <h2>سیستم قدیمی</h2>
                        <div>
                            <label for="ad-message-count">تعداد پیام‌های تبلیغاتی</label>
                            <div id="message-count-wrapper">
                                <select id="ad-message-count" class="form-control mb-3">
                                    <!-- گزینه‌ها از جاوا اسکریپت بارگذاری خواهند شد -->
                                </select>
                            </div>
                        </div>
                        <div id="ad-message-fields"></div>
                        <button id="save-button" class="btn btn-success mt-3" onclick="saveOldSystemData()">ذخیره</button>
                        <button id="delete-button" class="btn btn-danger mt-3" onclick="deleteWelcomeMessages()">حذف پیام‌های تبلیغاتی</button>


                        <div class="dashed-box mt-3">
                            <p>پیام‌های اصلی</p>
                            <div id="main-message-fields" class="message-fields-specific">
                            
                                <!-- پیام اول -->
                                <div class="input-group mb-3 d-flex justify-content-center">
                                    <label for="message-a" class="input-label" style="font-weight: bold; color: #bbb;">درخواست کیلومتر و شهر</label>
                                    <textarea id="message-a" class="main_message form-control" rows="4" style="margin-bottom: 15px; width: 90%;"></textarea>
                                </div>
                                <hr style="border-top: 1px dashed #ccc; width: 90%; margin: 10px auto;">
                        
                                <!-- پیام دوم -->
                                <div class="input-group mb-3 d-flex justify-content-center">
                                    <label for="message-b" class="input-label" style="font-weight: bold; color: #bbb;">دریافت شماره فروشنده</label>
                                    <textarea id="message-b" class="main_message form-control" rows="4" style="margin-bottom: 15px; width: 90%;"></textarea>
                                </div>
                                <hr style="border-top: 1px dashed #ccc; width: 90%; margin: 10px auto;">
                        
                                <!-- پیام سوم -->
                                <div class="input-group mb-3 d-flex justify-content-center">
                                    <label for="message-c" class="input-label" style="font-weight: bold; color: #bbb;">پیغام تایید برای مشتری</label>
                                    <textarea id="message-c" class="main_message form-control" rows="4" style="margin-bottom: 15px; width: 90%;"></textarea>
                                </div>
                            </div>
                        
                            <button id="save-button-main" class="btn btn-success mt-3" disabled>ذخیره</button>
                        </div>
                    </div>
                    <div id="sms-new" class="page" style="display: none;">
                        
                        <h2>سیستم جدید</h2>
                        <p>تنظیمات مربوط به سیستم جدید پیامک.</p>
                        <div id="new-system-container"></div>
                    </div>
                </div>
            </div>


            <!--dashboard -->
            <div id="dashboard" class="page" style="display: none;">
                <table class="table table-dark table-striped">
                    <thead>
                        <tr>
                            <th>تعداد کل استعلام‌ها</th>
                            <th>تعداد کل کالاهای تأیید شده</th>
                            <th>تعداد کل استعلام‌های تقلبی</th>
                        </tr>
                    </thead>
                    <tbody>
                        <!-- داده‌ها اینجا اضافه می‌شوند -->
                        <td id="total-requests">در حال بارگذاری...</td>
                        <td id="total-confirmed">---</td>
                        <td id="total-rejected">در حال بارگذاری...</td>
                
                    </tbody>
                </table>
 
                 <!-- chart -->

                 <div id="chart-container" style="width: 100%; height: 400px; position: relative;">
                    <canvas id="requestsChart" style="width: 100%; height: 100%;"></canvas>
                </div>
                
                <div class="filter-group">
                    <label for="start_date">تاریخ شروع:</label>
                    <input type="text" id="start_date" placeholder="انتخاب تاریخ شروع" class="form-control">
                    <label for="end_date">تاریخ پایان:</label>
                    <input type="text" id="end_date" placeholder="انتخاب تاریخ پایان" class="form-control">
                    <button id="filterChart" class="btn btn-primary mt-2">اعمال فیلتر</button>
                </div>
                



            </div>

            <div id="product-management-section" class="dashed-box mt-3" style="display: none;">

                <ul class="nav nav-tabs" id="productTabs">
                    <li class="nav-item">
                        <a class="nav-link active" id="add-serial-tab" href="javascript:void(0)" onclick="showTab('add-serial')">افزودن سریال</a>
                    </li>
                    <li class="nav-item">
                        <a class="nav-link" id="edit-buyer-tab" href="javascript:void(0)" onclick="showTab('edit-buyer')">ویرایش اطلاعات خریدار</a>
                    </li>
                    <li class="nav-item">
                        <a class="nav-link" id="existing-serials-tab" href="javascript:void(0)" onclick="showTab('existing-serials')">سریال‌های موجود</a>
                    </li>
                </ul>

                 <!-- محتوای تب‌ها -->
    
                 <div id="add-serial" class="tab-content" style="display: block;">
                <h3>افزودن سریال</h3>
                <h5 id="add-serial-label-top">مشتریان سیستم قدیمی</h5>
                    <div id="drop-zone" class="drop-zone">
                        <p>فایل اکسل خود را اینجا بکشید و رها کنید یا کلیک کنید برای انتخاب</p>
                        <input type="file" id="file-input" accept=".xls, .xlsx" hidden>         
                    </div>

                    <label for="table-select">نوع سریال</label>
                    <select id="add-serial-table-selector" class="form-select">
                    <option value="old_serials">مشتریان سیستم قدیمی</option>
                    <option value="new_serials">مشتریان سیستم جدید</option>
                    
                    </select>
                <!-- محتویات افزودن سریال سیستم جدید -->
                </div>



                <div id="edit-buyer" class="tab-content" style="display: none;">
                <h3>ویرایش اطلاعات خریدار</h3>
            
                <!-- Two columns with unique IDs -->
                <div class="row">
                    <!-- First Column: مشتریان سریالهای سیستم قدیمی -->
                
        
                <!-- Second Column: مشتریان سیستم های جدید -->
                <div id="new-system-customers" class="col-6">
                    <h4>جستجوی مشتریان </h4>
            
                    <!-- Dropdown for selecting search type -->
                    <select id="search-type" class="form-control mb-2">
                        <option value="serial">جستجو بر اساس سریال</option>
                        <option value="phone">جستجو بر اساس شماره مشتری</option>
                    </select>
            
                    <!-- Input for search query -->
                    <input type="text" id="search-input" class="form-control mb-2" placeholder="مقدار را وارد کنید">
            
                    <!-- Search Button -->
                    <button id="search-btn-new-customer" class="btn btn-primary mb-3">جستجو</button>
                    <hr>
                    <!-- Edit Fields -->
                    <div class="mt-3">
                        <label>سریال:</label>
                        <input type="text" id="edit-serial-newserials" class="form-control editClientTextBoxes" readonly>
            
                        <label>شماره موبایل مشتری:</label>
                        <input type="text" id="edit-phone-newserials" class="form-control editClientTextBoxes" readonly>
            
                        <label>کیلومتر حین ثبت گارانتی:</label>
                        <input type="text" id="edit-km-newserials" class="form-control editClientTextBoxes" readonly>
            
                        <div class="Time-date-container">
                            <label><strong>تاریخ ثبت گارانتی:</strong></label>
                            <hr>
                            <div class="form-group">
                                <label for="edit-year-new-serial">سال</label>
                                <select id="edit-year-new-serial" class="form-control editClientDrop" disabled>
                                    <option value="">انتخاب سال</option>
                                </select>
                            </div>
                            
                            <div class="form-group">
                                <label for="edit-month-new-serial">ماه</label>
                                <select id="edit-month-new-serial" class="form-control editClientDrop" disabled>
                                    <option value="">انتخاب ماه</option>
                                </select>
                            </div>
                            
                            <div class="form-group">
                                <label for="edit-day-new-serial">روز</label>
                                <select id="edit-day-new-serial" class="form-control editClientDrop" disabled>
                                    <option value="">انتخاب روز</option>
                                </select>
                            </div>
                        </div>
                        
            
                             <button id="save-btn-edit-new-serial-client" class="btn btn-success mt-2" disabled >ذخیره</button>

                              <button id="send-confirm-Sms-to-client" class="btn btn-primary" type="button" disabled >ارسال اس ام اس تایید ثبت گارانتی</button>
                          </div>
                      </div>
        
                   </div>
              </div>
    
              <div id="existing-serials" class="tab-content" style="display: none;">
                     <h3>سریال‌های موجود</h3>
                     <div id="loading-indicator" style="display: none;">Loading serials, please wait...</div>
                     <!-- Flex container for two columns -->
                      <div class="two-columns">
                   <!-- Left Column -->
                      <div class="left-column  half-width">
                        <h4>حذف سریال ها</h4>

                        <div id="drop-zone-delete" class="drop-zone">
                         <p>فایل CSV یا Excel را اینجا بکشید و رها کنید یا کلیک کنید</p>
                         <input type="file" id="file-input-delete" accept=".csv, .xls, .xlsx" hidden />
                        </div>
            
                       <p id="file-name-delete" class="file-name"></p>
                        <div id="first-column-rows-delete"></div>
                   </div>
          
                   <div class="right-column half-width">
                     <h4>دسته بندی سریالهای موجود</h4>

                      <!-- Group Buttons will be injected here -->
                     <div id="Sorted-serials-output" class="d-flex flex-wrap gap-2 mt-3 mb-3"></div>

                     <!-- Bootstrap Modal -->
                     <div id="serials-modal-container">
                         <div class="modal fade" id="serialModal" tabindex="-1" aria-labelledby="serialModalLabel" aria-hidden="true">
                               <div class="modal-dialog">
                                  <div class="modal-content">
                                        <div class="modal-header">
                                            <h5 class="modal-title" id="serialModalLabel">Serial Numbers</h5>
                                         <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
                                      </div>
                                      <div class="modal-body">
                                          <div id="serialList"></div>
                                          <div id="pagination" class="mt-3"></div>
                                       </div>
                                    </div>
                                </div>
                         </div>
                     </div>
                  </div>

                </div>
                </div>
                </div>
             <div id="lottery" class="page lottery-page" style="display: none;">
    <div class="lottery-container">
        <h3 class="lottery-page-title text-center">قرعه‌کشی مکانیک‌ها</h3>
        <p class="lottery-page-subtitle text-center">هر سریال ثبت‌شده ۱۰ امتیاز دارد — امتیاز بیشتر، شانس بیشتر در قرعه‌کشی</p>

        <!-- Section 1: Winner history -->
        <section class="lottery-card lottery-history-card">
            <h4 class="lottery-section-title">تاریخچه برندگان</h4>
            <div class="table-responsive">
                <table class="table table-striped table-bordered lottery-table text-center align-middle">
                    <thead>
                        <tr>
                            <th>شماره تماس</th>
                            <th>ماه و سال</th>
                            <th>روز</th>
                            <th class="lottery-admin-col">عملیات</th>
                        </tr>
                    </thead>
                    <tbody id="lottery-history-body">
                    </tbody>
                </table>
            </div>
            <p id="lottery-history-empty" class="lottery-empty-msg text-muted text-center" style="display: none;">هنوز برنده‌ای ثبت نشده است.</p>
        </section>

        <!-- Section 2: Weighted lottery draw -->
        <section class="lottery-card lottery-draw-card">
            <h4 class="lottery-section-title">قرعه‌کشی</h4>
            <div class="row g-2 mb-3 lottery-filters">
                <div class="col-md-4">
                    <label for="search-phone" class="form-label">شماره تماس</label>
                    <input type="text" id="search-phone" class="form-control" placeholder="جستجو بر اساس شماره تماس" oninput="filterTable()" />
                </div>
                <div class="col-md-4">
                    <label for="lottery-province" class="form-label">استان</label>
                    <select id="lottery-province" class="form-control">
                        <option value="">-- تمام استان‌ها --</option>
                    </select>
                </div>
                <div class="col-md-4">
                    <label for="lottery-city" class="form-label">شهر</label>
                    <select id="lottery-city" class="form-control">
                        <option value="">-- تمام شهرها --</option>
                    </select>
                </div>
            </div>
            <details id="lottery-debug-panel" class="lottery-debug-panel mb-3">
                <summary>گزارش وضعیت رابط قرعه‌کشی (لاگ)</summary>
                <pre id="lottery-trace-log" class="lottery-trace-log">در انتظار بارگذاری...</pre>
            </details>
            <div class="table-responsive">
                <table class="table table-striped lottery-table">
                    <thead>
                        <tr>
                            <th>تلفن</th>
                            <th>شهر</th>
                            <th>استان</th>
                            <th>تعداد سریال</th>
                            <th>امتیاز کل</th>
                            <th>احتمال نسبی</th>
                            <th class="lottery-admin-col">عملیات</th>
                        </tr>
                    </thead>
                    <tbody id="seller-table-body">
                    </tbody>
                </table>
            </div>
            <nav id="lottery-pagination" class="lottery-pagination d-flex justify-content-center align-items-center gap-3 mt-3" aria-label="صفحه‌بندی قرعه‌کشی">
                <button type="button" id="lottery-prev-page" class="btn btn-outline-secondary btn-sm">قبلی</button>
                <span id="lottery-page-info" class="lottery-page-info text-muted small"></span>
                <button type="button" id="lottery-next-page" class="btn btn-outline-secondary btn-sm">بعدی</button>
            </nav>
            <details class="lottery-suspended-panel mb-3">
                <summary>شماره‌های تعلیق‌شده از قرعه‌کشی</summary>
                <div class="table-responsive">
                    <table class="table table-sm table-striped lottery-table lottery-suspended-table text-center align-middle">
                        <thead>
                            <tr>
                                <th>شماره تماس</th>
                                <th>تعلیق</th>
                            </tr>
                        </thead>
                        <tbody id="lottery-suspended-body"></tbody>
                    </table>
                </div>
                <p id="lottery-suspended-empty" class="lottery-empty-msg text-muted text-center small">شماره تعلیق‌شده‌ای وجود ندارد.</p>
            </details>
            <p id="lottery-participants-empty" class="lottery-empty-msg text-muted text-center" style="display: none;">شرکت‌کننده‌ای با امتیاز وجود ندارد. مکانیک باید ابتدا کد M را پیامک کند.</p>
            <p id="lottery-participant-count" class="text-center text-muted small mb-2"></p>
            <div class="text-center mb-2">
                <button type="button" id="stom-backfill-button" class="btn btn-outline-secondary btn-sm">
                    همگام‌سازی سریال‌های S440000–S450000 با قرعه‌کشی
                </button>
            </div>
            <div class="text-center mt-3 lottery-draw-actions">
                <button type="button" id="select-winner-button" class="btn btn-primary btn-lg lottery-draw-btn">
                    شروع قرعه‌کشی
                </button>
            </div>
        </section>
    </div>

    <!-- Participant detail popup -->
    <div id="phone-detail-popup" class="lottery-popup" style="display: none;">
        <h4>جزئیات شرکت‌کننده</h4>
        <p><strong>تلفن:</strong> <span id="popup-phone"></span></p>
        <p><strong>شهر:</strong> <span id="popup-city"></span></p>
        <div class="lottery-popup-body">
            <h5>سریال‌های مرتبط:</h5>
            <ul id="popup-serials"></ul>
            <h5>توضیحات:</h5>
            <ul id="popup-descriptions"></ul>
        </div>
        <div class="lottery-popup-actions d-flex gap-2 justify-content-end flex-wrap mt-3">
            <button type="button" id="popup-reset-btn" class="btn btn-sm btn-warning">بازنشانی</button>
            <button type="button" id="popup-suspend-btn" class="btn btn-sm btn-danger">تعلیق</button>
            <button type="button" onclick="closePopup()" class="btn btn-sm btn-secondary">بستن</button>
        </div>
    </div>
</div>

<div id="image-verification" class="page" style="display: none;">
    <h3 class="text-center mb-3">تأیید تصویر سریال</h3>

    <section class="dashed-box mt-3 mb-4 iv-settings-card">
        <h4 class="text-center mb-3">فعال‌سازی سیستم تأیید تصویر</h4>
        <p class="small text-muted text-center mb-3">
            پس از تکمیل ثبت‌نام پیامکی، به‌جای پیام تأیید، لینک ثبت تصویر برای کاربر ارسال می‌شود.
            سریال‌های S440000 تا S450000 تحت گزینه M قرار می‌گیرند.
        </p>
        <div class="row justify-content-center g-3 mb-3">
            <div class="col-auto">
                <div class="form-check form-switch iv-toggle-switch">
                    <input class="form-check-input" type="checkbox" role="switch" id="iv-enable-s">
                    <label class="form-check-label" for="iv-enable-s"><strong>سریال S</strong></label>
                </div>
            </div>
            <div class="col-auto">
                <div class="form-check form-switch iv-toggle-switch">
                    <input class="form-check-input" type="checkbox" role="switch" id="iv-enable-m">
                    <label class="form-check-label" for="iv-enable-m"><strong>سریال M</strong> (شامل S440–S450)</label>
                </div>
            </div>
            <div class="col-auto">
                <div class="form-check form-switch">
                    <input class="form-check-input" type="checkbox" role="switch" id="iv-testers-only">
                    <label class="form-check-label" for="iv-testers-only"><strong>فقط شماره‌های تست</strong></label>
                </div>
            </div>
        </div>
        <p class="small text-muted text-center mb-3">با فعال بودن «فقط شماره‌های تست»، تأیید تصویر تنها برای شماره‌های ثبت‌شده در بخش تست پنل ادمین اعمال می‌شود.</p>
        <p id="iv-toggles-lock-notice" class="small text-warning text-center mb-3" style="display: none;"></p>
        <div class="row g-3 justify-content-center">
            <div class="col-md-12">
                <label for="iv-capture-url" class="form-label">آدرس صفحه ثبت تصویر</label>
                <input type="text" id="iv-capture-url" class="form-control" dir="ltr" placeholder="https://startechgroup.ir/warrantyDT/capture/">
            </div>
        </div>
        <details class="iv-sms-templates mt-3">
            <summary class="mb-2">تنظیم متن پیامک‌ها</summary>
            <div class="row g-3">
                <div class="col-12">
                    <label for="iv-sms-link" class="form-label">پیامک ارسال لینک ({serial}، {link})</label>
                    <textarea id="iv-sms-link" class="form-control" rows="2"></textarea>
                </div>
                <div class="col-md-6">
                    <label for="iv-sms-verified" class="form-label">پیامک تأیید اپراتور ({serial})</label>
                    <textarea id="iv-sms-verified" class="form-control" rows="2"></textarea>
                </div>
                <div class="col-md-6">
                    <label for="iv-sms-rejected-unreadable" class="form-label">پیامک اخطار: تصویر ناخوانا ({serial}، {link})</label>
                    <textarea id="iv-sms-rejected-unreadable" class="form-control" rows="2"></textarea>
                </div>
                <div class="col-md-6">
                    <label for="iv-sms-rejected-wrong-serial" class="form-label">پیامک اخطار: سریال نامرتبط ({serial}، {link})</label>
                    <textarea id="iv-sms-rejected-wrong-serial" class="form-control" rows="2"></textarea>
                </div>
                <div class="col-md-6">
                    <label for="iv-sms-rejected" class="form-label">پیامک رد عمومی (پشتیبان)</label>
                    <textarea id="iv-sms-rejected" class="form-control" rows="2"></textarea>
                </div>
            </div>
        </details>
        <div class="text-center mt-3">
            <button type="button" id="iv-settings-save-btn" class="btn btn-success" disabled>ذخیره تنظیمات</button>
            <p id="iv-settings-status" class="small text-muted mt-2 mb-0"></p>
        </div>
    </section>

    <h4 class="text-center mb-3">صف بررسی تصاویر</h4>
    <p class="text-center text-muted small mb-4">همه تصاویر ارسالی پس از تأیید اپراتور در بخش «تأیید شده» نگهداری می‌شوند</p>
    <div class="d-flex flex-wrap gap-2 justify-content-center mb-3">
        <button type="button" id="iv-filter-pending" class="btn btn-primary btn-sm active">در انتظار بررسی</button>
        <button type="button" id="iv-filter-verified" class="btn btn-outline-secondary btn-sm">تأیید شده</button>
        <button type="button" id="iv-filter-all" class="btn btn-outline-secondary btn-sm">همه</button>
        <button type="button" id="iv-refresh-btn" class="btn btn-outline-secondary btn-sm">بروزرسانی</button>
    </div>
    <p id="iv-list-status" class="small text-muted text-center"></p>
    <div class="table-responsive">
        <table class="table table-dark table-striped table-bordered text-center align-middle">
            <thead>
                <tr>
                    <th>سریال</th>
                    <th>تلفن</th>
                    <th>شهر</th>
                    <th>کیلومتر</th>
                    <th>نوع</th>
                    <th>تاریخ</th>
                    <th>وضعیت</th>
                    <th>رد</th>
                    <th>تصویر</th>
                </tr>
            </thead>
            <tbody id="iv-table-body"></tbody>
        </table>
    </div>
    <p id="iv-table-empty" class="text-muted text-center" style="display: none;">موردی برای نمایش وجود ندارد.</p>
    <nav class="d-flex justify-content-center align-items-center gap-3 mt-3">
        <button type="button" id="iv-prev-page" class="btn btn-outline-secondary btn-sm">قبلی</button>
        <span id="iv-page-info" class="text-muted small"></span>
        <button type="button" id="iv-next-page" class="btn btn-outline-secondary btn-sm">بعدی</button>
    </nav>
</div>

<div id="iv-review-overlay" class="iv-review-overlay" style="display: none;">
    <div class="iv-review-frame">
        <h4 class="mb-3" id="iv-review-title">بررسی تصویر سریال</h4>
        <div class="row g-3">
            <div class="col-md-6">
                <img id="iv-review-image" class="iv-review-image w-100 rounded" alt="تصویر سریال">
            </div>
            <div class="col-md-6">
                <ul class="list-unstyled iv-meta-list">
                    <li><strong>سریال:</strong> <span id="iv-meta-serial"></span></li>
                    <li><strong>تلفن:</strong> <span id="iv-meta-phone"></span></li>
                    <li><strong>شهر:</strong> <span id="iv-meta-city"></span></li>
                    <li><strong>کیلومتر:</strong> <span id="iv-meta-km"></span></li>
                    <li><strong>تاریخ:</strong> <span id="iv-meta-date"></span></li>
                    <li><strong>وضعیت:</strong> <span id="iv-meta-status"></span></li>
                </ul>
                <p id="iv-review-status" class="small text-muted mt-2"></p>
                <div class="d-flex flex-wrap gap-2 mt-3">
                    <button type="button" id="iv-verify-btn" class="btn btn-success">تأیید</button>
                    <button type="button" id="iv-reject-unreadable-btn" class="btn btn-warning">اخطار: تصویر ناخوانا</button>
                    <button type="button" id="iv-reject-wrong-serial-btn" class="btn btn-danger">اخطار: سریال نامرتبط</button>
                    <button type="button" id="iv-close-btn" class="btn btn-secondary">بستن</button>
                </div>
            </div>
        </div>
    </div>
</div>


<!-- Modal for Confirming Winner -->
<div class="modal fade" id="confirmWinnerModal" tabindex="-1" aria-labelledby="confirmWinnerModalLabel" aria-hidden="true" data-bs-backdrop="static" data-bs-keyboard="false">
  <div class="modal-dialog modal-lg modal-dialog-centered">
    <div class="modal-content lottery-winner-modal">
      <div class="modal-header lottery-winner-modal__header">
        <h5 class="modal-title lottery-winner-modal__title" id="confirmWinnerModalLabel">
          <i class="fas fa-gift lottery-winner-modal__icon" aria-hidden="true"></i>
          قرعه‌کشی محصولات استارتک
        </h5>
        <button type="button" class="btn-close btn-close-white lottery-winner-modal__close" id="lottery-modal-close-btn" data-bs-dismiss="modal" aria-label="بستن" style="display: none;"></button>
      </div>
      <div class="modal-body lottery-winner-modal__body">
        <div id="lottery-draw-phase">
          <p id="lottery-draw-status" class="lottery-draw-status text-center mb-3">در حال قرعه‌کشی…</p>
          <div class="lottery-digit-panel">
            <div class="lottery-digit-panel__screen">
              <div id="lottery-digit-reel" class="lottery-digit-reel" aria-live="polite"></div>
              <div id="lottery-countdown" class="lottery-countdown" aria-live="polite">30</div>
            </div>
          </div>
        </div>
        <div id="lottery-confirm-phase" style="display: none;">
          <p class="mb-1">برنده قرعه‌کشی:</p>
          <p class="lottery-admin-full-phone mb-1"><strong id="selected-phone-display"></strong></p>
          <p class="text-muted small mb-2">شماره کامل (فقط مدیر)</p>
          <p class="text-muted mb-0">امتیاز: <span id="selected-score-display"></span></p>
          <p class="mt-3">آیا این شماره به عنوان برنده ثبت شود؟ پس از تأیید، امتیاز این شرکت‌کننده امروز صفر می‌شود.</p>
        </div>
      </div>
      <div class="modal-footer lottery-winner-modal__footer" id="lottery-confirm-footer" style="display: none;">
        <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">انصراف</button>
        <button type="button" id="confirm-winner-btn" class="btn btn-primary">تأیید</button>
      </div>
    </div>
  </div>
</div>







             <div id="inquiries" class="page" style="display: none;">
                <!-- Filter Bar -->
                <div class="card mb-3">
    <div class="card-body">
        <h5 class="card-title">فیلتر استعلام‌ها</h5>
        
        <form id="filter-form">
            <!-- First Row - Main Filters -->
            <div class="row align-items-end"> <!-- Added align-items-end for vertical alignment -->
                <!-- Phone Filter -->
                <div class="col-md-3">
                    <label for="phone-filter">شماره مشتری</label>
                    <input type="text" class="form-control" id="phone-filter" placeholder="جستجو بر اساس شماره مشتری">
                </div>
                
                <!-- City Filter -->
                <div class="col-md-3">
                    <label for="city-filter">شهر</label>
                    <input type="text" class="form-control" id="city-filter" placeholder="جستجو بر اساس شهر">
                </div>
                
                <!-- Province Dropdown - Fixed Structure -->
                <div class="col-md-3">
                    <label for="Ostan">استان</label>
                    <select id="Ostan" class="form-control">
                        <option value=""></option>
                                                    <option value="  ,آذرشهر ,اسکو ,اهر ,بستان‌آباد ,بناب ,تبریز ,جلفا ,چاراویماق ,سراب ,شبستر ,عجب‌شیر ,کلیبر ,مراغه ,مرند ,ملکان ,میانه ,ورزقان ,هریس ,هشترود">آذربایجان شرقی</option>
                                        <option value="  ,ارومیه ,اشنویه ,بوکان ,پیرانشهر ,تکاب ,چالدران ,خوی ,سردشت ,سلماس ,شاهین‌دژ ,ماکو ,مهاباد ,میاندوآب ,نقده">آذربایجان غربی</option>
                                        <option value="  ,اردبیل ,بیله‌سوار ,پارس‌آباد ,خلخال ,کوثر ,گِرمی ,مِشگین‌شهر ,نَمین ,نیر">اردبیل</option>
                                        <option value="  ,آران و بیدگل ,اردستان ,اصفهان ,برخوار و میمه ,تیران و کرون ,چادگان ,خمینی‌شهر ,خوانسار ,سمیرم ,شهرضا ,سمیرم سفلی ,فریدن ,فریدون‌شهر ,فلاورجان ,کاشان ,گلپایگان ,لنجان ,مبارکه ,نائین ,نجف‌آباد ,نطنز">اصفهان</option>
                                        <option value="  ,آبدانان ,ایلام ,ایوان ,دره‌شهر ,دهلران ,شیروان و چرداول ,مهران">ایلام</option>
                                        <option value="  ,بوشهر ,تنگستان ,جم ,دشتستان ,دشتی,دیر ,دیلم ,کنگان ,گناوه">بوشهر</option>
                                        <option value="  ,اسلام‌شهر ,پاکدشت ,تهران ,دماوند ,رباط‌کریم ,ری ,ساوجبلاغ ,شمیرانات ,شهریار ,فیروزکوه ,کرج ,نظرآباد ,ورامین">تهران</option>
                                        <option value="  ,اردل ,بروجن ,شهرکرد ,فارسان ,کوهرنگ ,لردگان">چهارمحال و بختیاری</option>
                                        <option value="  ,بیرجند ,درمیان ,سرایان ,سربیشه ,فردوس ,قائنات,نهبندان">خراسان جنوبی</option>
                                        <option value="  ,بردسکن ,تایباد ,تربت جام ,تربت حیدریه ,چناران ,خلیل‌آباد ,خواف ,درگز ,رشتخوار ,سبزوار ,سرخس ,فریمان ,قوچان ,کاشمر ,کلات ,گناباد ,مشهد ,مه ولات ,نیشابور">خراسان رضوی</option>
                                        <option value="  ,اسفراین ,بجنورد ,جاجرم ,شیروان ,فاروج ,مانه و سملقان">خراسان شمالی</option>
                                        <option value="  ,آبادان ,امیدیه ,اندیمشک ,اهواز ,ایذه ,باغ‌ملک ,بندر ماهشهر ,بهبهان ,خرمشهر ,دزفول ,دشت آزادگان ,رامشیر ,رامهرمز ,شادگان ,شوش ,شوشتر ,گتوند ,لالی ,مسجد سلیمان,هندیجان ">خوزستان</option>
                                        <option value="  ,ابهر ,ایجرود ,خدابنده ,خرمدره ,زنجان ,طارم ,ماه‌نشان">زنجان</option>
                                        <option value="  ,دامغان ,سمنان ,شاهرود ,گرمسار ,مهدی‌شهر">سمنان</option>
                                        <option value="  ,ایرانشهر ,چابهار ,خاش ,دلگان ,زابل ,زاهدان ,زهک ,سراوان ,سرباز ,کنارک ,نیک‌شهر">سیستان و بلوچستان</option>
                                        <option value="  ,آباده ,ارسنجان ,استهبان ,اقلید ,بوانات ,پاسارگاد ,جهرم ,خرم‌بید ,خنج ,داراب ,زرین‌دشت ,سپیدان ,شیراز ,فراشبند ,فسا ,فیروزآباد ,قیر و کارزین ,کازرون ,لارستان ,لامِرد ,مرودشت ,ممسنی ,مهر ,نی‌ریز">فارس</option>
                                        <option value="  ,آبیک ,البرز ,بوئین‌زهرا ,تاکستان ,قزوین">قزوین</option>
                                        <option value="  ,قم">قم</option>
                                        <option value="  ,بانه ,بیجار ,دیواندره ,سروآباد ,سقز ,سنندج ,قروه ,کامیاران ,مریوان">کردستان</option>
                                        <option value="  ,بافت ,بردسیر ,بم ,جیرفت ,راور ,رفسنجان ,رودبار جنوب ,زرند ,سیرجان ,شهر بابک ,عنبرآباد ,قلعه گنج ,کرمان ,کوهبنان ,کهنوج ,منوجان">کرمان</option>
                                        <option value="  ,اسلام‌آباد غرب ,پاوه ,ثلاث باباجانی ,جوانرود ,دالاهو ,روانسر ,سرپل ذهاب ,سنقر ,صحنه ,قصر شیرین ,کرمانشاه ,کنگاور ,گیلان غرب ,هرسین">کرمانشاه</option>
                                        <option value="  ,بویراحمد ,بهمئی ,دنا ,کهگیلویه ,گچساران">کهگیلویه و بویراحمد</option>
                                        <option value="  ,آزادشهر ,آق‌قلا ,بندر گز ,ترکمن ,رامیان ,علی‌آباد ,کردکوی ,کلاله ,گرگان ,گنبد کاووس ,مراوه‌تپه ,مینودشت">گلستان</option>
                                        <option value="  ,آستارا ,آستانه اشرفیه ,اَملَش ,بندر انزلی ,رشت ,رضوانشهر ,رودبار ,رودسر ,سیاهکل ,شَفت ,صومعه‌سرا ,طوالش ,فومَن ,لاهیجان ,لنگرود ,ماسال">گیلان</option>
                                        <option value="  ,ازنا ,الیگودرز ,بروجرد ,پل‌دختر ,خرم‌آباد ,دورود ,دلفان ,سلسله ,کوهدشت">لرستان</option>
                                        <option value="  ,آمل ,بابل ,بابلسر ,بهشهر ,تنکابن ,جویبار ,چالوس ,رامسر ,ساری ,سوادکوه ,قائم‌شهر ,گلوگاه ,محمودآباد ,نکا ,نور ,نوشهر">مازندران</option>
                                        <option value="  ,آشتیان ,اراک ,تفرش ,خمین ,دلیجان ,زرندیه ,ساوه ,شازند ,کمیجان ,محلات">مرکزی</option>
                                        <option value="  ,ابوموسی ,بستک ,بندر عباس ,بندر لنگه ,جاسک ,حاجی‌آباد ,شهرستان خمیر ,رودان  ,قشم ,گاوبندی ,میناب">هرمزگان</option>
                                        <option value="  ,اسدآباد ,بهار ,تویسرکان ,رزن ,کبودرآهنگ ,ملایر ,نهاوند ,همدان">همدان</option>
                                        <option value="  ,ابرکوه ,اردکان ,بافق ,تفت ,خاتم ,صدوق ,طبس ,مهریز ,مِیبُد ,یزد">یزد</option>
                        <!-- Other options remain the same -->
                    </select>
                </div>
                
                <!-- Serial Filter -->
                <div class="col-md-3">
                    <label for="serial-filter">سریال</label>
                    <input type="text" class="form-control" id="serial-filter" placeholder="جستجو بر اساس سریال">
                </div>
            </div>
            
            <!-- Second Row - Date Filters -->
            <div class="row mt-3">
                <div class="col-md-3">
                    <label for="time-filter-from">تاریخ ثبت از</label>
                    <input type="text" class="form-control" id="time-filter-from" placeholder="از تاریخ">
                </div>
                <div class="col-md-3">
                    <label for="time-filter-to">تاریخ ثبت تا</label>
                    <input type="text" class="form-control" id="time-filter-to" placeholder="تا تاریخ">
                </div>
                
                <!-- Apply Filter Button -->
                <div class="col-md-3 d-flex align-items-end">
                    <button type="button" class="btn btn-warning" id="apply-filters-qr">🔍 اعمال فیلتر</button>

                </div>
            </div>
            
            <!-- Action Buttons -->
            <div class="d-flex mt-3">
                <button type="submit" class="btn btn-primary me-2">انتخاب بازی زمانی</button>
                <button type="button" class="btn btn-success me-2" id="export-csv">📂 خروجی CSV</button>
                <button type="button" class="btn btn-danger" id="export-pdf">📄 خروجی PDF</button>
            </div>
        </form>
    </div>
</div>
            
                <!-- Table for inquiries -->
                <div class="table-responsive">
                    <table class="table table-striped">
                        <thead>
                            <tr>
                                <th>شماره مشتری</th>
                                <th>سریال</th>
                                <th>کیلومتر</th>
                                <th> شهر</th>
                                <!-- Make the date header clickable for sorting -->
                                <th id="time-header" style="cursor: pointer;">تاریخ ثبت <span id="time-sort-indicator"></span></th>
                            </tr>
                        </thead>
                        <tbody id="inquiries-table-body">
                            <!-- Data will be inserted here dynamically -->
                        </tbody>
                    </table>
                </div>
            </div>
            <div id="admins" class="page" style="display: none;">
                <h6>    صفحه مدیریت کاربران</h6>
                <script>  console.log(userRole);  </script>
                
                 <!-- Section to change password (initially hidden) -->
                                <!-- PASSWORD CHANGE, CREATE USER, AND USER LIST SECTIONS -->
                                    <!-- Wrapper container with RTL layout -->
                    <div class="container-fluid d-flex justify-content-start gap-3" dir="rtl">
                        <!-- User List Section (rightmost on screen) -->
                        <div id="user-list-section" class="card p-4 shadow-sm" style="width: 350px; border: 1px solid #ddd; border-radius: 10px; display: none;">
                            <h6 class="text-center mb-3">لیست کاربران</h6>
                            <ul id="user-list" class="list-group">
                                <!-- Users will be loaded here dynamically -->
                            </ul>
                        </div>

                        <!-- Create User Section -->
                        <div id="create-user-section" class="card p-4 shadow-sm" style="width: 350px; border: 1px solid #ddd; border-radius: 10px; display: none;">
                            <h6 class="text-center mb-3">ایجاد کاربر جدید</h6>
                            <form id="create-user-form">
                                <div class="mb-3">
                                    <label for="username" class="form-label">نام کاربری:</label>
                                    <div class="input-group">
                                        <span class="input-group-text"><i class="fa fa-user"></i></span>
                                        <input type="text" class="form-control" id="username" name="username" required>
                                    </div>
                                </div>
                                <div class="mb-3">
                                    <label for="user-password" class="form-label">رمز عبور:</label>
                                    <div class="input-group">
                                        <span class="input-group-text"><i class="fa fa-lock"></i></span>
                                        <input type="password" class="form-control" id="user-password" name="user-password" required>
                                    </div>
                                </div>
                                <div class="mb-3">
                                    <label class="form-label">نقش کاربر:</label>
                                    <div class="dropdown w-100">
                                        <button class="btn btn-secondary dropdown-toggle w-100" type="button" id="userRoleDropdown" data-bs-toggle="dropdown" aria-expanded="false">
                                            انتخاب نقش
                                        </button>
                                        <ul class="dropdown-menu w-100">
                                            <li><a class="dropdown-item" href="#" onclick="selectRole(4, 'مدیر')">مدیر </a></li>
                                            <li><a class="dropdown-item" href="#" onclick="selectRole(1, 'گزارش گیر')">گزارش گیر</a></li>
                                        </ul>
                                    </div>
                                    <input type="hidden" id="selectedRole" name="selectedRole" value="">
                                </div>
                                <button type="submit" class="btn btn-success w-100">ایجاد کاربر</button>
                            </form>
                        </div>

                        <!-- Password Change Section -->
                        <div id="password-change-section" class="card p-4 shadow-sm" style="width: 350px; border: 1px solid #ddd; border-radius: 10px; display: none;">
                        <h6 class="text-center mb-3">تغییر رمز عبور</h6>
                        <form id="password-change-form">
                            <div class="mb-3">
                                <label for="new-password" class="form-label">رمز عبور جدید:</label>
                                <div class="input-group">
                                    <span class="input-group-text"><i class="fa fa-lock"></i></span>
                                    <input type="password" class="form-control" id="new-password" name="new-password" required>
                                </div>
                            </div>
                            <div class="mb-3">
                                <label for="confirm-password" class="form-label">تایید رمز عبور:</label>
                                <div class="input-group">
                                    <span class="input-group-text"><i class="fa fa-lock"></i></span>
                                    <input type="password" class="form-control" id="confirm-password" name="confirm-password" required>
                                </div>
                            </div>
                            <button type="submit" class="btn btn-primary w-100">تغییر رمز عبور</button>
                        </form>
                    </div>

                        <!-- Test phones section -->
                        <div id="test-phones-section" class="card p-4 shadow-sm" style="width: 380px; border: 1px solid #ddd; border-radius: 10px; display: none;">
                            <h6 class="text-center mb-3">شماره‌های تست</h6>
                            <p class="small text-muted">شماره‌هایی که برای تست SMS و قرعه‌کشی استفاده می‌کنید. «پاکسازی داده تست» همه ثبت‌های آن‌ها را از تمام جداول حذف می‌کند و سریال‌ها را آزاد می‌کند.</p>
                            <div class="mb-2">
                                <label for="test-phone-input" class="form-label">شماره موبایل</label>
                                <input type="text" id="test-phone-input" class="form-control" placeholder="09121234567" dir="ltr">
                            </div>
                            <div class="mb-3">
                                <label for="test-phone-label" class="form-label">برچسب (اختیاری)</label>
                                <input type="text" id="test-phone-label" class="form-control" placeholder="مثلاً گوشی تست ۱">
                            </div>
                            <button type="button" id="test-phone-add-btn" class="btn btn-success w-100 mb-3">افزودن شماره تست</button>
                            <ul id="test-phones-list" class="list-group mb-3"></ul>
                            <p id="test-phones-empty" class="text-muted small text-center" style="display: none;">هنوز شماره تستی ثبت نشده است.</p>
                            <button type="button" id="test-phone-clear-btn" class="btn btn-warning w-100 mb-2">پاکسازی داده تست</button>
                            <pre id="test-phones-summary" class="small text-muted mb-0" style="white-space: pre-wrap;"></pre>
                        </div>

                        <!-- Preset lottery winner (server RAM) -->
                        <div id="lottery-preset-winner-section" class="card p-4 shadow-sm" style="width: 380px; border: 1px solid #ddd; border-radius: 10px; display: none;">
                            <h6 class="text-center mb-3">برنده از پیش تعیین‌شده قرعه‌کشی</h6>
                            <p class="small text-muted">اگر شماره ذخیره شود، قرعه‌کشی بدون محاسبه احتمال انجام می‌شود و فقط انیمیشن ۳۰ ثانیه‌ای نمایش داده می‌شود. با ری‌استارت سرور پاک می‌شود.</p>
                            <div class="mb-2">
                                <label for="lottery-preset-winner-input" class="form-label">شماره برنده</label>
                                <input type="text" id="lottery-preset-winner-input" class="form-control" placeholder="09121234567" dir="ltr">
                            </div>
                            <div class="d-flex gap-2 mb-3">
                                <button type="button" id="lottery-preset-winner-save" class="btn btn-warning flex-fill">ذخیره</button>
                                <button type="button" id="lottery-preset-winner-clear" class="btn btn-outline-secondary flex-fill">پاک کردن</button>
                            </div>
                            <p id="lottery-preset-winner-status" class="small text-muted mb-0">در حال بارگذاری...</p>
                        </div>


                    </div>


                        <!-- SCRIPT SECTION -->
                        <script>
                            // Show sections if userRole >= 5
                            if (userRole >= 5) {
                                document.getElementById('password-change-section').style.display = 'block';
                                document.getElementById('create-user-section').style.display = 'block';
                                document.getElementById('user-list-section').style.display = 'block';
                                document.getElementById('test-phones-section').style.display = 'block';
                                document.getElementById('lottery-preset-winner-section').style.display = 'block';
                            }

                            // Role selection
                            function selectRole(value, text) {
                                document.getElementById('userRoleDropdown').innerText = text;
                                document.getElementById('selectedRole').value = value;
                            }

                            // Handle create user form
                            document.getElementById('create-user-form').addEventListener('submit', function(event) {
                                event.preventDefault();

                                let username = document.getElementById('username').value.trim();
                                let password = document.getElementById('user-password').value.trim();
                                let role = document.getElementById('selectedRole').value;

                                if (!role) {
                                    alert('لطفاً نقش کاربر را انتخاب کنید!');
                                    return;
                                }

                                let userData = { username, password, role };

                                fetch('create_user.php', {
                                    method: 'POST',
                                    headers: { 'Content-Type': 'application/json' },
                                    body: JSON.stringify(userData)
                                })
                                .then(response => response.json())
                                .then(data => {
                                    if (data.success) {
                                        alert('کاربر با موفقیت ایجاد شد!');
                                        document.getElementById('create-user-form').reset();
                                        document.getElementById('userRoleDropdown').innerText = 'انتخاب نقش';
                                    } else {
                                        alert('خطا در ایجاد کاربر: ' + data.message);
                                    }
                                })
                                .catch(error => {
                                    console.error('Error:', error);
                                    alert('مشکلی در ارسال درخواست وجود دارد.');
                                });
                            });
                        </script>

                <div class="modal fade" id="deleteUserModal" tabindex="-1" aria-labelledby="deleteUserModalLabel" aria-hidden="true">
                    <div class="modal-dialog">
                        <div class="modal-content">
                            <div class="modal-header">
                                <h5 class="modal-title" id="deleteUserModalLabel">حذف کاربر</h5>
                                <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
                            </div>
                            <div class="modal-body">
                                آیا مطمئن هستید که می‌خواهید این کاربر را حذف کنید؟
                            </div>
                            <div class="modal-footer">
                                <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">لغو</button>
                                <button type="button" class="btn btn-danger" id="confirmDeleteUser">حذف</button>
                            </div>
                        </div>
                    </div>
                </div>                            
            </div>        

            
</main>
<div id="popup" style="display: none;">
    <div class="popup-content">
        <h2 id="label-in-serial-insert-popup">سریال های جدید</h2>
        <ul id="serial-list"></ul>
        <button id="save-serials" class="btn btn-success">ذخیره سریال ها</button>
        <button id="export-duplicates" class="btn btn-info">خروجی گرفتن از تکراری‌ها</button> <!-- دکمه جدید -->
        <button id="close-popup" class="btn btn-danger">بستن</button>
    </div>
</div>
<div id="popup-delete" style="display: none;">
    <div class="popup-content">
        <h2>مدیریت سریال‌ها</h2>
        <ul id="serial-list-delete">
            <!-- Dynamically populated list of serial numbers -->
        </ul>
        <button id="delete-serials">حذف سریال‌ها</button>
        <button id="close-popup-delete">بستن</button>
    </div>
</div>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/jspdf/2.5.1/jspdf.umd.min.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/jspdf-autotable/3.5.30/jspdf.plugin.autotable.min.js"></script>

    <script src="https://cdn.jsdelivr.net/npm/jquery@3.6.0/dist/jquery.min.js"></script>
    <!-- بارگذاری persianDate -->
    <script src="https://cdn.jsdelivr.net/npm/persian-date@1.1.0/dist/persian-date.min.js"></script>
    <!-- بارگذاری persian-datepicker -->
    <script src="https://cdn.jsdelivr.net/npm/persian-datepicker@1.2.0/dist/js/persian-datepicker.min.js"></script>


    <script src="farvardin.min.js"></script>
    <!-- لینک به فایل جاوا اسکریپت جداگانه -->
    <script src="scripts.js?v=4"></script>
    <script src="save_main_sms.js"></script>
    <script src="winner-sms-settings.js?v=2"></script>
    <script src="image-verification-settings.js?v=6"></script>
    <script src="image-verification.js?v=4"></script>
    <script src="newSms.js"></script>
    <script src="productPanel.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/xlsx/0.18.5/xlsx.full.min.js"></script>
    <script src="search_customer_new.js"></script>
    <script src="edit-save-newserial-client-info.js"></script>
    <script src="delete-serial-drop-zone.js"></script>
    <script src="report-all.js"></script>
    <script src="record-chart.js"></script>
    <script src="chart-calendar.js"></script>
    <script src="province-utils.js"></script>
    <script src="filter-search.js"></script>
    <script src="export-report.js"></script>
    <script src ="loadUsers.js"></script>
    <script src="test-phones.js?v=1"></script>  
    <script src="passwordChange.js"></script>
    <script src="lottery-ui.js?v=2"></script>
    <script src="lottery-admin-actions.js?v=3"></script>
    <script src="lottery-preset-winner.js?v=3"></script>
    <script src="https://cdn.jsdelivr.net/npm/canvas-confetti@1.9.3/dist/confetti.browser.min.js"></script>
    <script src="get-sellers.js?v=2"></script>
    <script src="lottery-draw-animation.js?v=3"></script>
    <script src="winner-select.js?v=5"></script>
    <script src="get_winners.js"></script>
    <script src="get_serials_in_rage.js"></script>
    <script>
    document.addEventListener("DOMContentLoaded", function () {
      $("#time-filter-from").persianDatepicker({
        format: "YYYY-MM-DD", autoClose: true,
        toolbox: { calendarSwitch: { enabled: false } },
        onSelect: () => $("#time-filter-to").persianDatepicker("enable")
      });
      $("#time-filter-to").persianDatepicker({
        format: "YYYY-MM-DD", autoClose: true,
        toolbox: { calendarSwitch: { enabled: false } },
        onSelect: () => $("#time-filter-from").persianDatepicker("enable")
      });
      let allInquiries = [];
      const toEnglishDigits = str => {
        const per = ["۰","۱","۲","۳","۴","۵","۶","۷","۸","۹"];
        const eng = ["0","1","2","3","4","5","6","7","8","9"];
        return per.reduce((s, p, i) => s.replace(new RegExp(p, "g"), eng[i]), str);
      };
      const renderTable = data => {
        const tbody = document.getElementById("inquiries-table-body");
        tbody.innerHTML = "";
        data.forEach(r => {
          const sh = r.time
                   ? farvardin.gregorianToSolar(...r.time.split("-").map(Number), "string")
                   : "---";
          const tr = document.createElement("tr");
          tr.innerHTML = `
            <td>${r.phone  || '---'}</td>
            <td>${r.serial || '---'}</td>
            <td>${r.km     || '---'}</td>
            <td>${r.city   || '---'}</td>
            <td>${sh}</td>`;
          tbody.appendChild(tr);
        });
      };

      // ————————————————
      // D) Initial blank render
      // ————————————————
      renderTable([]);

      // ————————————————
      // E) Date-range fetch (server side)
      // ————————————————
      document.getElementById("filter-form")
        .addEventListener("submit", function (e) {
          e.preventDefault();
          let from = toEnglishDigits(this["time-filter-from"].value);
          let to   = toEnglishDigits(this["time-filter-to"].value);
          from = farvardin.solarToGregorian(...from.split("-").map(Number), "string");
          to   = farvardin.solarToGregorian(...to.split("-").map(Number), "string");

          fetch("fetch_inquiries.php", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ start_date: from, end_date: to })
          })
          .then(r => r.json())
          .then(arr => {
            allInquiries = Array.isArray(arr) ? arr : [];
            renderTable(allInquiries);
          })
          .catch(err => {
            console.error(err);
            alert("خطا در دریافت اطلاعات. لطفاً کنسول را بررسی کنید.");
          });
        });

      // ————————————————
      // F) Client-side “Quick-Filter” (phone, serial, province & city)
      // ————————————————
      document.getElementById("apply-filters-qr")
        .addEventListener("click", function () {
          const phoneF  = document.getElementById("phone-filter" ).value.trim();
          const serialF = document.getElementById("serial-filter").value.trim();
          const cityF   = document.getElementById("city-filter" ).value.trim().toLowerCase();
          const ostanEl = document.getElementById("Ostan");
          const ostanOpt = ostanEl && ostanEl.selectedIndex >= 0 ? ostanEl.options[ostanEl.selectedIndex] : null;
          const provinceCities = ostanOpt && ostanOpt.value ? extractProvinceCities(ostanOpt.value) : [];

          const filtered = allInquiries.filter(row => {
            if (!row.phone.toString().startsWith(phoneF))   return false;
            if (!row.serial.toString().startsWith(serialF)) return false;

            if (provinceCities.length && !provinceCities.includes(row.city)) return false;
            if (cityF && !row.city.toLowerCase().startsWith(cityF)) return false;

            return true;
          });

          renderTable(filtered);
        });

      // ————————————————
      // G) Sorting by date column
      // ————————————————
      const timeHeader  = document.getElementById("time-header");
      const timeInd     = document.getElementById("time-sort-indicator");
      let sortOrder = "desc";
      const updateArrow = () => timeInd.textContent = sortOrder === "asc" ? "▲" : "▼";
      updateArrow();

      timeHeader.addEventListener("click", () => {
        sortOrder = (sortOrder === "asc" ? "desc" : "asc");
        updateArrow();
        const sorted = allInquiries.slice().sort((a,b) =>
          sortOrder === "asc"
            ? a.time.localeCompare(b.time)
            : b.time.localeCompare(a.time)
        );
        renderTable(sorted);
      });
    });
  </script>                           
</body>
</html>
