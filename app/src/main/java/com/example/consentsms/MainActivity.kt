package com.example.consentsms

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.consentsms.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val reportItems = mutableListOf<RecipientReport>()
    private val scheduleItems = mutableListOf<ScheduledMessage>()
    private lateinit var reportAdapter: ReportAdapter
    private lateinit var scheduleAdapter: ScheduleAdapter

    private var repeatCount = 3
    private var scheduledMillis: Long? = null
    private var currentMessage = ""
    private var currentNumbers = listOf<String>()
    private var awaitingReportIndex: Int? = null
    private var recipientInputMode = RecipientInputMode.MANUAL
    private var campaignPaused = false
    private var smsComposerWasOpened = false

    private val formatter = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault())
    private val prefs by lazy { getSharedPreferences("consent_sms_prefs", Context.MODE_PRIVATE) }
    private val smsComposerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        handleSmsComposerReturn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        reportAdapter = ReportAdapter(reportItems)
        scheduleAdapter = ScheduleAdapter(scheduleItems)
        binding.reportRecycler.layoutManager = LinearLayoutManager(this)
        binding.reportRecycler.adapter = reportAdapter
        binding.scheduleRecycler.layoutManager = LinearLayoutManager(this)
        binding.scheduleRecycler.adapter = scheduleAdapter

        setupUi()
        loadSchedules()
        updateRepeatText()
        updateCharCount()
        updateRecipientModeUi()
        updateRangeSummary()
        handleScheduledLaunch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleScheduledLaunch(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            updateDefaultSmsStatus()
            if (smsComposerWasOpened && awaitingReportIndex != null) {
                handleSmsComposerReturn()
            }
        }
    }

    private fun setupUi() {
        binding.minusRepeat.setOnClickListener {
            if (repeatCount > 1) repeatCount--
            updateRepeatText()
        }
        binding.plusRepeat.setOnClickListener {
            if (repeatCount < 20) repeatCount++
            updateRepeatText()
        }

        binding.messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateCharCount()
            }
        })

        binding.rangeStartInput.addTextChangedListener(rangeTextWatcher)
        binding.rangeEndInput.addTextChangedListener(rangeTextWatcher)

        binding.manualModeButton.setOnClickListener {
            recipientInputMode = RecipientInputMode.MANUAL
            updateRecipientModeUi()
        }
        binding.rangeModeButton.setOnClickListener {
            recipientInputMode = RecipientInputMode.RANGE
            updateRecipientModeUi()
        }

        binding.previewButton.setOnClickListener { preparePreview() }
        binding.continueButton.setOnClickListener { confirmCampaignStart() }
        binding.openNextButton.setOnClickListener { openNextSms() }
        binding.markSuccessButton.setOnClickListener { markCurrentAttempt(success = true) }
        binding.markFailedButton.setOnClickListener { markCurrentAttempt(success = false) }
        binding.pauseQueueButton.setOnClickListener { toggleQueuePause() }
        binding.simSettingsButton.setOnClickListener { openSimSettings() }
        binding.defaultSmsSettingsButton.setOnClickListener { openDefaultSmsSettings() }
        binding.menuButton.setOnClickListener { showAppMenu() }

        binding.dateButton.setOnClickListener { pickDate() }
        binding.timeButton.setOnClickListener { pickTime() }
        binding.scheduleButton.setOnClickListener { createSchedule() }

        binding.navSetup.setOnClickListener { showScreen(Screen.SETUP) }
        binding.navPreview.setOnClickListener { showScreen(Screen.PREVIEW) }
        binding.navStatus.setOnClickListener { showScreen(Screen.STATUS) }

        updateStats()
        updateDefaultSmsStatus()
    }

    private fun preparePreview(forceStayOnPreview: Boolean = true) {
        val numbers = resolveRecipients(showErrors = true) ?: return
        val message = binding.messageInput.text.toString().trim()

        if (numbers.isEmpty()) {
            toast("حداقل یک شماره معتبر وارد کن")
            return
        }
        if (message.isBlank()) {
            toast("متن پیام را وارد کن")
            return
        }

        currentNumbers = numbers
        currentMessage = message
        awaitingReportIndex = null
        campaignPaused = false
        updatePauseButton()
        binding.statusTitleText.text = "صف ارسال آماده است"

        reportItems.clear()
        numbers.forEach { reportItems.add(RecipientReport(number = it, totalAttempts = repeatCount)) }
        reportAdapter.refresh()
        updateStats()
        updateCurrentTargetUi()

        binding.previewMetaText.text = "برای ${numbers.size} شماره | تکرار برای هر شماره: $repeatCount"
        binding.previewMessageText.text = message
        binding.previewFooterText.text = "این پیام برای ${numbers.size} شماره، هر کدام $repeatCount بار آماده خواهد شد."

        if (forceStayOnPreview) {
            showScreen(Screen.PREVIEW)
        } else {
            showScreen(Screen.STATUS)
        }
    }

    private fun confirmCampaignStart() {
        if (!ensureDefaultSmsApp()) return
        if (reportItems.isEmpty() || currentNumbers.isEmpty()) {
            preparePreview(forceStayOnPreview = true)
            if (reportItems.isEmpty() || currentNumbers.isEmpty()) return
        }

        val totalAttempts = reportItems.sumOf { it.totalAttempts }
        AlertDialog.Builder(this)
            .setTitle("تأیید شروع صف ارسال")
            .setMessage(
                "${currentNumbers.size} گیرنده و $totalAttempts تلاش در صف قرار می‌گیرد. " +
                    "برای هر پیام، برنامهٔ SMS گوشی باز می‌شود و ارسال با تأیید شما انجام خواهد شد."
            )
            .setNegativeButton("انصراف", null)
            .setPositiveButton("تأیید و شروع") { _, _ ->
                campaignPaused = false
                updatePauseButton()
                binding.statusTitleText.text = "صف ارسال آماده است"
                binding.statusMessageText.text =
                    "۱) پیام را باز کن  ۲) سیم‌کارت را در برنامه SMS انتخاب و ارسال کن  ۳) برگرد و نتیجه را ثبت کن"
                showScreen(Screen.STATUS)
                updateCurrentTargetUi()
            }
            .show()
    }

    private fun openNextSms() {
        if (campaignPaused) {
            toast("صف متوقف است؛ ابتدا روی «ادامه صف» بزن")
            return
        }
        if (reportItems.isEmpty()) {
            preparePreview(forceStayOnPreview = true)
            if (reportItems.isNotEmpty()) toast("پیش‌نمایش را بررسی و شروع صف را تأیید کن")
            return
        }

        if (awaitingReportIndex != null) {
            toast("ابتدا نتیجه مورد قبلی را ثبت کن")
            return
        }

        val nextIndex = reportItems.indexOfFirst { it.remainingAttempts > 0 }
        if (nextIndex == -1) {
            binding.statusTitleText.text = "صف ارسال کامل شد"
            binding.statusMessageText.text = "همه تلاش‌ها کامل شده‌اند"
            toast("ارسال‌ها به پایان رسید")
            updateCurrentTargetUi()
            return
        }

        val report = reportItems[nextIndex]
        val intent = createSmsComposerIntent(report.number)
        if (intent == null) {
            binding.statusTitleText.text = "برنامه پیامک تنظیم نشده است"
            binding.statusMessageText.text =
                "ابتدا یک برنامه را به‌عنوان SMS پیش‌فرض انتخاب کن و دوباره تلاش کن."
            toast("برنامه پیامک روی دستگاه پیدا نشد")
            return
        }

        awaitingReportIndex = nextIndex
        smsComposerWasOpened = true
        binding.statusTitleText.text = "برنامه پیامک در حال بازشدن است"
        binding.statusMessageText.text =
            "برای ${report.number}، سیم‌کارت ۱ یا ۲ را در برنامه SMS انتخاب کن؛ سپس برگرد و نتیجه را ثبت کن."
        smsComposerLauncher.launch(intent)
        showScreen(Screen.STATUS)
        updateCurrentTargetUi()
    }

    private fun handleSmsComposerReturn() {
        if (!::binding.isInitialized || !smsComposerWasOpened || awaitingReportIndex == null) return
        smsComposerWasOpened = false
        binding.statusTitleText.text = "نتیجه ارسال را ثبت کن"
        binding.statusMessageText.text =
            "اگر پیام را با سیم‌کارت موردنظر فرستادی، «ثبت موفق» و در غیر این صورت «ثبت ناموفق» را بزن."
        showScreen(Screen.STATUS)
    }

    private fun toggleQueuePause() {
        if (reportItems.isEmpty()) {
            toast("هنوز صف ارسالی ساخته نشده است")
            return
        }
        campaignPaused = !campaignPaused
        updatePauseButton()
        binding.statusTitleText.text = if (campaignPaused) "صف متوقف شده است" else "صف ارسال آماده است"
        binding.statusMessageText.text = if (campaignPaused) {
            "صف متوقف شد؛ نتیجه پیام بازشده را می‌توانی ثبت کنی"
        } else {
            "صف دوباره فعال شد"
        }
    }

    private fun updatePauseButton() {
        binding.pauseQueueButton.text = if (campaignPaused) "ادامه صف" else "توقف صف"
        binding.openNextButton.isEnabled = !campaignPaused
        binding.openNextButton.alpha = if (campaignPaused) 0.55f else 1f
    }

    private fun markCurrentAttempt(success: Boolean) {
        val index = awaitingReportIndex
        if (index == null) {
            toast("ابتدا روی «باز کردن پیام برای مورد بعدی» بزن")
            return
        }
        val report = reportItems[index]
        if (success) report.successCount++ else report.failedCount++
        awaitingReportIndex = null
        reportAdapter.refresh()
        updateStats()
        updateCurrentTargetUi()
        binding.statusTitleText.text = "صف آماده ادامه است"
        binding.statusMessageText.text = if (success) "ارسال برای ${report.number} ثبت شد" else "ارسال برای ${report.number} ناموفق ثبت شد"
    }

    private fun updateStats() {
        val sent = reportItems.sumOf { it.successCount }
        val failed = reportItems.sumOf { it.failedCount }
        val total = reportItems.sumOf { it.totalAttempts }
        val remaining = total - sent - failed

        binding.sentCountText.text = sent.toString()
        binding.failedCountText.text = failed.toString()
        binding.remainingCountText.text = remaining.coerceAtLeast(0).toString()

        val completed = sent + failed
        val progress = if (total == 0) 0 else ((completed * 100f) / total).toInt()
        binding.progressBar.progress = progress
        binding.progressLabelText.text = "پیشرفت: $completed از $total"
        reportAdapter.refresh()
    }

    private fun updateCurrentTargetUi() {
        val activeIndex = awaitingReportIndex ?: reportItems.indexOfFirst { it.remainingAttempts > 0 }
        val completed = reportItems.sumOf { it.completedAttempts }
        val total = reportItems.sumOf { it.totalAttempts }
        if (activeIndex == -1 || reportItems.isEmpty()) {
            binding.currentTargetText.text = "در حال ارسال برای: -"
            binding.progressLabelText.text = "پیشرفت: $completed از $total"
            return
        }

        val item = reportItems[activeIndex]
        val currentAttempt = item.completedAttempts + if (awaitingReportIndex == activeIndex) 1 else 0
        binding.currentTargetText.text = "در حال ارسال برای: ${item.number}"
        binding.progressLabelText.text =
            "پیشرفت: $completed از $total  |  تکرار ${currentAttempt.coerceAtLeast(1)} از ${item.totalAttempts}"
    }

    private fun createSchedule() {
        if (!ensureNotificationPermission()) return
        val parsed = resolveRecipients(showErrors = true) ?: return
        val message = binding.messageInput.text.toString().trim()
        val time = scheduledMillis

        if (parsed.isEmpty()) {
            toast("ابتدا شماره‌های معتبر را وارد کن")
            return
        }
        if (message.isBlank()) {
            toast("متن پیام خالی است")
            return
        }
        if (time == null) {
            toast("تاریخ و ساعت را انتخاب کن")
            return
        }
        if (time <= System.currentTimeMillis()) {
            toast("زمان انتخاب‌شده باید در آینده باشد")
            return
        }

        val item = ScheduledMessage(
            id = System.currentTimeMillis(),
            numbersText = parsed.joinToString("\n"),
            message = message,
            repeatCount = repeatCount,
            triggerAtMillis = time
        )
        scheduleItems.add(0, item)
        saveSchedules()
        scheduleAdapter.refresh()
        scheduleAlarm(item)
        showScreen(Screen.STATUS)
        toast("زمان‌بندی ذخیره شد")
    }

    private fun scheduleAlarm(item: ScheduledMessage) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val requestCode = (item.id % Int.MAX_VALUE).toInt()
        val intent = Intent(this, ScheduleReceiver::class.java).apply {
            putExtra("numbersText", item.numbersText)
            putExtra("messageText", item.message)
            putExtra("repeatCount", item.repeatCount)
            putExtra("requestCode", requestCode)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerAtMillis, pendingIntent)
    }

    private fun saveSchedules() {
        val array = JSONArray()
        scheduleItems.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("numbersText", item.numbersText)
                    put("message", item.message)
                    put("repeatCount", item.repeatCount)
                    put("triggerAtMillis", item.triggerAtMillis)
                }
            )
        }
        prefs.edit().putString("schedules", array.toString()).apply()
    }

    private fun loadSchedules() {
        scheduleItems.clear()
        val raw = prefs.getString("schedules", null) ?: return
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            scheduleItems.add(
                ScheduledMessage(
                    id = obj.getLong("id"),
                    numbersText = obj.getString("numbersText"),
                    message = obj.getString("message"),
                    repeatCount = obj.getInt("repeatCount"),
                    triggerAtMillis = obj.getLong("triggerAtMillis")
                )
            )
        }
        scheduleAdapter.refresh()
    }

    private fun pickDate() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val base = Calendar.getInstance().apply {
                    timeInMillis = scheduledMillis ?: System.currentTimeMillis()
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                }
                scheduledMillis = base.timeInMillis
                updateScheduleSummary()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun pickTime() {
        val cal = Calendar.getInstance()
        TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                val base = Calendar.getInstance().apply {
                    timeInMillis = scheduledMillis ?: System.currentTimeMillis()
                    set(Calendar.HOUR_OF_DAY, hourOfDay)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                }
                scheduledMillis = base.timeInMillis
                updateScheduleSummary()
            },
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun updateScheduleSummary() {
        binding.scheduleSummaryText.text = scheduledMillis?.let {
            "ارسال در: ${formatter.format(Date(it))}"
        } ?: "زمانی انتخاب نشده است"
    }

    private fun updateRepeatText() {
        binding.repeatText.text = repeatCount.toString()
    }

    private fun updateCharCount() {
        binding.charCountText.text = "تعداد کاراکتر: ${binding.messageInput.text?.length ?: 0}"
    }

    private fun showAppMenu() {
        val options = arrayOf(
            "راهنمای ارسال و سیم‌کارت",
            "تنظیمات سیم‌کارت و شبکه",
            "انتخاب برنامه پیش‌فرض SMS",
            "درباره برنامه"
        )
        AlertDialog.Builder(this)
            .setTitle("منوی Consent SMS")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSendHelp()
                    1 -> openSimSettings()
                    2 -> openDefaultSmsSettings()
                    3 -> showAboutDialog()
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun showSendHelp() {
        AlertDialog.Builder(this)
            .setTitle("روش ارسال پیام")
            .setMessage(
                "۱) صف را بساز و پیش‌نمایش را تأیید کن.\n\n" +
                    "۲) روی «باز کردن پیام برای مورد بعدی» بزن.\n\n" +
                    "۳) در برنامه پیامک گوشی، سیم‌کارت ۱ یا ۲ را انتخاب و پیام را ارسال کن.\n\n" +
                    "۴) به Consent SMS برگرد و نتیجه را با «ثبت موفق» یا «ثبت ناموفق» مشخص کن."
            )
            .setPositiveButton("متوجه شدم", null)
            .show()
    }

    private fun openSimSettings() {
        val settingsIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
        if (settingsIntent.resolveActivity(packageManager) != null) {
            startActivity(settingsIntent)
        } else {
            toast("صفحه تنظیمات سیم‌کارت روی این دستگاه پیدا نشد")
        }
    }

    private fun openDefaultSmsSettings() {
        val settingsIntent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        if (settingsIntent.resolveActivity(packageManager) != null) {
            startActivity(settingsIntent)
        } else {
            toast("صفحه برنامه‌های پیش‌فرض روی این دستگاه پیدا نشد")
        }
    }

    private fun updateDefaultSmsStatus() {
        if (!::binding.isInitialized) return
        val packageName = Telephony.Sms.getDefaultSmsPackage(this)
        if (packageName.isNullOrBlank()) {
            binding.smsAppStatusText.text = "برنامه پیش‌فرض SMS تنظیم نشده است"
            binding.smsAppStatusText.setTextColor(getColor(R.color.accent_red))
            return
        }

        val appLabel = runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
        binding.smsAppStatusText.text = "برنامه پیامک آماده است: $appLabel"
        binding.smsAppStatusText.setTextColor(getColor(R.color.accent_green))
    }

    private fun ensureDefaultSmsApp(): Boolean {
        if (!Telephony.Sms.getDefaultSmsPackage(this).isNullOrBlank()) return true
        AlertDialog.Builder(this)
            .setTitle("برنامه پیش‌فرض SMS انتخاب نشده")
            .setMessage("برای بازکردن مطمئن پیام و انتخاب سیم‌کارت، ابتدا برنامه پیامک پیش‌فرض گوشی را انتخاب کن.")
            .setNegativeButton("فعلاً نه", null)
            .setPositiveButton("بازکردن تنظیمات") { _, _ -> openDefaultSmsSettings() }
            .show()
        return false
    }

    private fun createSmsComposerIntent(number: String): Intent? {
        val baseIntent = Intent(
            Intent.ACTION_SENDTO,
            Uri.fromParts("smsto", number, null)
        ).apply {
            putExtra("sms_body", currentMessage)
            putExtra(Intent.EXTRA_TEXT, currentMessage)
        }

        val defaultPackage = Telephony.Sms.getDefaultSmsPackage(this)
        if (!defaultPackage.isNullOrBlank()) {
            val targetedIntent = Intent(baseIntent).setPackage(defaultPackage)
            if (targetedIntent.resolveActivity(packageManager) != null) {
                return targetedIntent
            }
        }

        return baseIntent.takeIf { it.resolveActivity(packageManager) != null }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("درباره Consent SMS")
            .setMessage(
                "نسخه ۱.۳\n\n" +
                    "آماده‌سازی امن پیامک، تولید بازه شماره، پیش‌نمایش، زمان‌بندی و گزارش دستی. " +
                    "این برنامه پیامک را مخفیانه یا بدون تأیید کاربر ارسال نمی‌کند."
            )
            .setPositiveButton("بستن", null)
            .show()
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun updateRecipientModeUi() {
        val isManual = recipientInputMode == RecipientInputMode.MANUAL
        if (!isManual && repeatCount != 1) {
            repeatCount = 1
            updateRepeatText()
        }
        binding.minusRepeat.isEnabled = isManual
        binding.plusRepeat.isEnabled = isManual
        binding.minusRepeat.alpha = if (isManual) 1f else 0.4f
        binding.plusRepeat.alpha = if (isManual) 1f else 0.4f
        binding.repeatHintText.text = if (isManual) {
            "برای هر گیرنده چند بار آماده‌سازی شود"
        } else {
            "در حالت بازه، هر شماره یک بار آماده می‌شود"
        }
        binding.manualInputGroup.visibility = if (isManual) View.VISIBLE else View.GONE
        binding.rangeInputGroup.visibility = if (isManual) View.GONE else View.VISIBLE
        binding.manualModeButton.setBackgroundResource(
            if (isManual) R.drawable.mode_selected else R.drawable.mode_unselected
        )
        binding.rangeModeButton.setBackgroundResource(
            if (isManual) R.drawable.mode_unselected else R.drawable.mode_selected
        )
        binding.manualModeButton.setTextColor(getColor(if (isManual) R.color.white else R.color.text_muted))
        binding.rangeModeButton.setTextColor(getColor(if (isManual) R.color.text_muted else R.color.white))
        updateRangeSummary()
    }

    private fun updateRangeSummary() {
        val start = normalizeIranMobile(binding.rangeStartInput.text?.toString().orEmpty())
        val end = normalizeIranMobile(binding.rangeEndInput.text?.toString().orEmpty())
        binding.rangeSummaryText.text = when {
            binding.rangeStartInput.text.isNullOrBlank() || binding.rangeEndInput.text.isNullOrBlank() ->
                "شماره شروع و پایان را وارد کنید"
            start == null || end == null ->
                "هر دو شماره باید یک شماره موبایل معتبر ایران باشند"
            end.toLong() < start.toLong() ->
                "شماره پایان باید بزرگ‌تر یا مساوی شماره شروع باشد"
            end.toLong() - start.toLong() + 1 > MAX_RANGE_RECIPIENTS ->
                "این بازه بیشتر از سقف $MAX_RANGE_RECIPIENTS شماره است"
            else -> {
                val count = end.toLong() - start.toLong() + 1
                "این بازه شامل $count شماره است: $start تا $end"
            }
        }
    }

    private fun resolveRecipients(showErrors: Boolean): List<String>? {
        return when (recipientInputMode) {
            RecipientInputMode.MANUAL -> {
                val parsed = parseRecipients(binding.numbersInput.text.toString())
                if (parsed.size > MAX_MANUAL_RECIPIENTS) {
                    if (showErrors) toast("حداکثر $MAX_MANUAL_RECIPIENTS شماره در هر صف مجاز است")
                    null
                } else {
                    parsed
                }
            }
            RecipientInputMode.RANGE -> buildRangeRecipients(showErrors)
        }
    }

    private fun buildRangeRecipients(showErrors: Boolean): List<String>? {
        val start = normalizeIranMobile(binding.rangeStartInput.text?.toString().orEmpty())
        val end = normalizeIranMobile(binding.rangeEndInput.text?.toString().orEmpty())
        if (start == null || end == null) {
            if (showErrors) toast("شماره شروع و پایان معتبر وارد کن")
            return null
        }

        val startValue = start.toLong()
        val endValue = end.toLong()
        if (endValue < startValue) {
            if (showErrors) toast("شماره پایان باید بعد از شماره شروع باشد")
            return null
        }

        val count = endValue - startValue + 1
        if (count > MAX_RANGE_RECIPIENTS) {
            if (showErrors) toast("حداکثر بازه مجاز $MAX_RANGE_RECIPIENTS شماره است")
            return null
        }
        if (!binding.rangeConsentCheck.isChecked) {
            if (showErrors) toast("ابتدا تأیید کن که گیرندگان برای دریافت پیام رضایت دارند")
            return null
        }

        return (startValue..endValue).map { value ->
            value.toString().padStart(11, '0')
        }
    }

    private fun ensureNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST
        )
        toast("برای یادآوری زمان‌بندی، مجوز اعلان را تأیید کن و دوباره ثبت زمان‌بندی را بزن")
        return false
    }

    private fun showScreen(screen: Screen) {
        binding.setupScreen.visibility = if (screen == Screen.SETUP) View.VISIBLE else View.GONE
        binding.previewScreen.visibility = if (screen == Screen.PREVIEW) View.VISIBLE else View.GONE
        binding.statusScreen.visibility = if (screen == Screen.STATUS) View.VISIBLE else View.GONE
        styleNav(binding.navSetup, screen == Screen.SETUP)
        styleNav(binding.navPreview, screen == Screen.PREVIEW)
        styleNav(binding.navStatus, screen == Screen.STATUS)
    }

    private fun styleNav(view: TextView, selected: Boolean) {
        if (selected) {
            view.setBackgroundResource(R.drawable.bottom_nav_item_selected)
            view.setTextColor(getColor(R.color.text_main))
        } else {
            view.background = null
            view.setTextColor(getColor(R.color.text_muted))
        }
    }

    private fun parseRecipients(raw: String): List<String> {
        return raw
            .split(Regex("[,;\\s]+"))
            .mapNotNull { normalizeIranMobile(it) }
            .distinct()
    }

    private fun normalizeIranMobile(input: String): String? {
        var n = input.trim()
        if (n.isEmpty()) return null
        n = n.replace("-", "").replace("(", "").replace(")", "")
        if (n.startsWith("+98")) n = "0" + n.removePrefix("+98")
        if (n.startsWith("0098")) n = "0" + n.removePrefix("0098")
        return if (Regex("^09\\d{9}$").matches(n)) n else null
    }

    private fun handleScheduledLaunch(intent: Intent?) {
        if (intent?.getBooleanExtra("fromSchedule", false) != true) return
        recipientInputMode = RecipientInputMode.MANUAL
        updateRecipientModeUi()
        binding.numbersInput.setText(intent.getStringExtra("numbersText") ?: "")
        binding.messageInput.setText(intent.getStringExtra("messageText") ?: "")
        repeatCount = intent.getIntExtra("repeatCount", 1)
        updateRepeatText()
        updateCharCount()
        preparePreview(forceStayOnPreview = false)
        binding.statusTitleText.text = "صف زمان‌بندی‌شده آماده است"
        binding.statusMessageText.text = "زمان‌بندی فعال شد؛ برای ادامه روی «باز کردن پیام برای مورد بعدی» بزن"
        showScreen(Screen.STATUS)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private val rangeTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = updateRangeSummary()
    }

    private enum class Screen { SETUP, PREVIEW, STATUS }
    private enum class RecipientInputMode { MANUAL, RANGE }

    companion object {
        private const val MAX_MANUAL_RECIPIENTS = 500
        private const val MAX_RANGE_RECIPIENTS = 500L
        private const val NOTIFICATION_PERMISSION_REQUEST = 1101
    }
}
