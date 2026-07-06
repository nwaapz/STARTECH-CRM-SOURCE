package com.example.smsgateway

data class SmsMessagePlaceholderHint(
    val token: String,
    val labelFa: String,
)

data class SmsMessageDefinition(
    val id: SmsMessageId,
    val categoryFa: String,
    val titleFa: String,
    val descriptionFa: String,
    val defaultTemplate: String,
    val placeholders: List<SmsMessagePlaceholderHint>,
    val sampleValues: Map<String, String> = emptyMap(),
)

object SmsMessageCatalog {
    const val EXAMPLE_CITY = "تبریز"
    const val EXAMPLE_KM = "3350"
    const val EXAMPLE_SERIAL = "M100"

    val definitions: List<SmsMessageDefinition> = listOf(
        SmsMessageDefinition(
            id = SmsMessageId.REPLY_VALID,
            categoryFa = "نتیجه ثبت",
            titleFa = "ثبت موفق",
            descriptionFa = "ارسال پس از ثبت معتبر سریال",
            defaultTemplate = "شماره سریال شما معتبر است و گارانتی شما آغاز شد.",
            placeholders = emptyList(),
        ),
        SmsMessageDefinition(
            id = SmsMessageId.REPLY_INVALID,
            categoryFa = "نتیجه ثبت",
            titleFa = "سریال نامعتبر",
            descriptionFa = "سریال ناموجود، استفاده‌شده، یا پیام خالی",
            defaultTemplate = "شماره سریال معتبر نیست.",
            placeholders = emptyList(),
        ),
        SmsMessageDefinition(
            id = SmsMessageId.SESSION_EXPIRED,
            categoryFa = "ثبت مرحله‌ای",
            titleFa = "انقضای جلسه",
            descriptionFa = "پایان مهلت ثبت مرحله‌ای",
            defaultTemplate = "زمان ثبت به پایان رسید. لطفاً مجدداً سریال را ارسال کنید.",
            placeholders = emptyList(),
        ),
        SmsMessageDefinition(
            id = SmsMessageId.STEP_PROMPT_FIELD,
            categoryFa = "ثبت مرحله‌ای",
            titleFa = "درخواست فیلد بعدی",
            descriptionFa = "درخواست ارسال فیلد بعدی در ثبت مرحله‌ای",
            defaultTemplate = "لطفاً @1 را ارسال کنید.",
            placeholders = listOf(
                SmsMessagePlaceholderHint("@1", "نام فیلد (شماره سریال / شهر / کیلومتر)"),
            ),
            sampleValues = mapOf("@1" to "شهر"),
        ),
        SmsMessageDefinition(
            id = SmsMessageId.STEP_PROMPT_CITY_KM,
            categoryFa = "ثبت مرحله‌ای",
            titleFa = "درخواست شهر و کیلومتر",
            descriptionFa = "مرحله دوم در حالت «شهر+کیلومتر در یک پیام»",
            defaultTemplate = "لطفاً شهر و کیلومتر را در یک پیام ارسال کنید. مثال: @1 @2",
            placeholders = listOf(
                SmsMessagePlaceholderHint("@1", "شهر نمونه"),
                SmsMessagePlaceholderHint("@2", "کیلومتر نمونه"),
            ),
            sampleValues = mapOf("@1" to EXAMPLE_CITY, "@2" to EXAMPLE_KM),
        ),
        SmsMessageDefinition(
            id = SmsMessageId.KM_INVALID,
            categoryFa = "ثبت مرحله‌ای",
            titleFa = "کیلومتر نامعتبر",
            descriptionFa = "کیلومتر عددی نیست (مرحله‌ای / ترکیبی)",
            defaultTemplate = "کیلومتر باید عدد باشد.",
            placeholders = emptyList(),
        ),
        SmsMessageDefinition(
            id = SmsMessageId.COMBINED_MISSING_CITY,
            categoryFa = "ثبت ترکیبی",
            titleFa = "شهر وارد نشده",
            descriptionFa = "خطای پارس — شهر خالی",
            defaultTemplate = "لطفاً نام شهر را در پیام وارد کنید.",
            placeholders = emptyList(),
        ),
        SmsMessageDefinition(
            id = SmsMessageId.COMBINED_MISSING_KM,
            categoryFa = "ثبت ترکیبی",
            titleFa = "کیلومتر نامعتبر",
            descriptionFa = "خطای پارس — کیلومتر خالی یا غیرعددی",
            defaultTemplate = "کیلومتر باید عدد باشد.",
            placeholders = emptyList(),
        ),
        SmsMessageDefinition(
            id = SmsMessageId.COMBINED_FORMAT_ERROR,
            categoryFa = "ثبت ترکیبی",
            titleFa = "فرمت نادرست",
            descriptionFa = "سریال، شهر و کیلومتر در یک پیام لازم است",
            defaultTemplate = "لطفاً سریال، شهر و کیلومتر را در یک پیام ارسال کنید. مثال: @1 @2 @3",
            placeholders = listOf(
                SmsMessagePlaceholderHint("@1", "سریال نمونه"),
                SmsMessagePlaceholderHint("@2", "شهر نمونه"),
                SmsMessagePlaceholderHint("@3", "کیلومتر نمونه"),
            ),
            sampleValues = mapOf(
                "@1" to EXAMPLE_SERIAL,
                "@2" to EXAMPLE_CITY,
                "@3" to EXAMPLE_KM,
            ),
        ),
        SmsMessageDefinition(
            id = SmsMessageId.PARTIAL_MISSING_CITY_KM,
            categoryFa = "تکمیل ثبت ناقص",
            titleFa = "درخواست شهر و کیلومتر",
            descriptionFa = "ثبت ناقص — هر دو فیلد",
            defaultTemplate = "لطفاً شهر و کیلومتر را ارسال کنید. مثال: @1",
            placeholders = listOf(
                SmsMessagePlaceholderHint("@1", "مثال (سریال + شهر + کیلومتر)"),
            ),
            sampleValues = mapOf("@1" to "$EXAMPLE_SERIAL $EXAMPLE_CITY $EXAMPLE_KM"),
        ),
        SmsMessageDefinition(
            id = SmsMessageId.PARTIAL_MISSING_CITY,
            categoryFa = "تکمیل ثبت ناقص",
            titleFa = "درخواست شهر",
            descriptionFa = "ثبت ناقص — فقط شهر",
            defaultTemplate = "لطفاً نام شهر را در پیام وارد کنید. مثال: @1",
            placeholders = listOf(
                SmsMessagePlaceholderHint("@1", "مثال (سریال + شهر)"),
            ),
            sampleValues = mapOf("@1" to "$EXAMPLE_SERIAL $EXAMPLE_CITY"),
        ),
        SmsMessageDefinition(
            id = SmsMessageId.KM_INVALID_WITH_EXAMPLE,
            categoryFa = "تکمیل ثبت ناقص",
            titleFa = "کیلومتر نامعتبر با مثال",
            descriptionFa = "ثبت ناقص — کیلومتر نامعتبر",
            defaultTemplate = "کیلومتر باید عدد باشد. مثال: @1",
            placeholders = listOf(
                SmsMessagePlaceholderHint("@1", "مثال (سریال + کیلومتر)"),
            ),
            sampleValues = mapOf("@1" to "$EXAMPLE_SERIAL $EXAMPLE_KM"),
        ),
        SmsMessageDefinition(
            id = SmsMessageId.COMPLETION_REMINDER,
            categoryFa = "یادآوری تکمیل",
            titleFa = "یادآوری تکمیل",
            descriptionFa = "ارسال از گزارش‌ها — حالت ترکیبی یا سریال",
            defaultTemplate = "ثبت گارانتی شما ناقص است. لطفاً @1 را ارسال کنید. مثال: @2",
            placeholders = listOf(
                SmsMessagePlaceholderHint("@1", "فیلدهای ناقص (شهر / کیلومتر / شهر و کیلومتر)"),
                SmsMessagePlaceholderHint("@2", "مثال کامل"),
            ),
            sampleValues = mapOf(
                "@1" to "شهر و کیلومتر",
                "@2" to "$EXAMPLE_SERIAL $EXAMPLE_CITY $EXAMPLE_KM",
            ),
        ),
        SmsMessageDefinition(
            id = SmsMessageId.COMPLETION_REMINDER_STEP_COMBINED,
            categoryFa = "یادآوری تکمیل",
            titleFa = "یادآوری — مرحله ترکیبی",
            descriptionFa = "حالت مرحله‌ای + شهر و کیلومتر در یک پیام",
            defaultTemplate = "ثبت گارانتی شما ناقص است. ابتدا سریال را ارسال کنید: @1 سپس شهر و کیلومتر را در یک پیام بفرستید. مثال: @2 @3",
            placeholders = listOf(
                SmsMessagePlaceholderHint("@1", "شماره سریال"),
                SmsMessagePlaceholderHint("@2", "شهر نمونه"),
                SmsMessagePlaceholderHint("@3", "کیلومتر نمونه"),
            ),
            sampleValues = mapOf(
                "@1" to EXAMPLE_SERIAL,
                "@2" to EXAMPLE_CITY,
                "@3" to EXAMPLE_KM,
            ),
        ),
        SmsMessageDefinition(
            id = SmsMessageId.COMPLETION_REMINDER_STEP_SEQUENTIAL,
            categoryFa = "یادآوری تکمیل",
            titleFa = "یادآوری — مرحله‌ای",
            descriptionFa = "حالت مرحله‌ای + ترتیب جداگانه",
            defaultTemplate = "ثبت گارانتی شما ناقص است. لطفاً مراحل زیر را در پیام‌های جداگانه ارسال کنید: @1",
            placeholders = listOf(
                SmsMessagePlaceholderHint("@1", "فهرست مراحل"),
            ),
            sampleValues = mapOf(
                "@1" to "سریال: $EXAMPLE_SERIAL، شهر: $EXAMPLE_CITY، کیلومتر: $EXAMPLE_KM",
            ),
        ),
    )

    fun definitionFor(id: SmsMessageId): SmsMessageDefinition =
        definitions.first { it.id == id }

    fun categoriesInOrder(): List<String> =
        definitions.map { it.categoryFa }.distinct()
}
