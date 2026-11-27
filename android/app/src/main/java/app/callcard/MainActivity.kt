package app.callcard

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private var tab = 0                 // 0 명함·문구, 1 발송 설정, 2 기록
    private var logFilter = 0           // 0 전체, 1 발송, 2 안 보냄
    private var previewType = MsgType.BASIC
    private var setupExpanded = false
    private var testStartedAt = 0L

    private val required: Array<String>
        get() = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.SEND_SMS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshAll()
            if (!allGranted()) showPermissionHelp()
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { saveCard(it) } }

    /** 백그라운드에서 기록·설정이 바뀌면 화면에 바로 반영 */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { if (!isFinishing) refreshLive() }
    }

    // ------------------------------------------------------------------ 생명주기

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        Notifier.createChannels(this)
        tab = savedInstanceState?.getInt("tab") ?: 0

        v<TextView>(R.id.tvVersion).text = "v${BuildConfig.VERSION_NAME}"
        v<View>(R.id.btnUpdate).visibility = if (Updater.configured) View.VISIBLE else View.GONE
        v<View>(R.id.btnUpdate).setOnClickListener { checkUpdate(manual = true) }

        setupSwitch()
        setupTabs()
        setupCardPage()
        setupSendPage()
        setupLogPage()

        UpdateCheckWorker.schedule(this)
        if (savedInstanceState == null) checkUpdate(manual = intent.getBooleanExtra("checkUpdate", false))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onStart() { super.onStart(); prefs.listen(prefsListener) }
    override fun onStop() { prefs.unlisten(prefsListener); super.onStop() }
    override fun onResume() { super.onResume(); refreshAll() }

    // ------------------------------------------------------------------ 상단 상태

    private fun setupSwitch() {
        val sw = v<Switch>(R.id.swEnabled)
        sw.isChecked = prefs.enabled
        sw.setOnCheckedChangeListener { button, on ->
            if (!on) { prefs.enabled = false; refreshAll(); return@setOnCheckedChangeListener }
            val reason = when {
                !allGranted() -> "먼저 '처음 설정'의 권한 허용을 마쳐 주세요."
                !prefs.hasCard && prefs.intro.isBlank() -> "명함 이미지나 소개글을 먼저 넣어 주세요."
                else -> null
            }
            if (reason != null) {
                button.isChecked = false
                toast(reason)
                setupExpanded = true
                refreshAll()
                v<ScrollView>(R.id.scroll).smoothScrollTo(0, 0)
            } else {
                prefs.enabled = true
                refreshAll()
            }
        }
    }

    private fun refreshStatus() {
        val on = prefs.enabled
        v<Switch>(R.id.swEnabled).let { if (it.isChecked != on) it.isChecked = on }
        v<TextView>(R.id.tvStatus).text = if (on) "작동 중" else "꺼져 있음"
        v<View>(R.id.statusDot).backgroundTintList =
            ColorStateList.valueOf(if (on) color(R.color.mint) else 0xFF5B6B80.toInt())
        val count = prefs.sentTodayCount()
        v<TextView>(R.id.tvStatusSub).text = when {
            !on && setupDoneCount() < STEPS -> "처음 설정을 마치고 스위치를 켜 주세요."
            !on -> "스위치를 켜면 통화가 끝난 뒤 명함을 보냅니다."
            prefs.autoSend -> "확인 없이 자동 발송  ·  오늘 ${count}건 보냄"
            else -> "확인 후 발송  ·  오늘 ${count}건 보냄\n통화 후 뜨는 알림에서 [보내기]를 눌러야 발송됩니다."
        }
    }

    // ------------------------------------------------------------------ 처음 설정 체크리스트

    private class Step(val title: String, val desc: String, val done: Boolean, val action: String, val onClick: () -> Unit)

    private fun steps(): List<Step> {
        val pm = getSystemService(PowerManager::class.java)
        return listOf(
            Step("권한 허용", "전화 · 통화 기록 · 문자 · 알림", allGranted(), "허용") { permLauncher.launch(required) },
            Step("배터리 최적화 제외", "통화 감지가 늦거나 빠지는 것을 막습니다", pm.isIgnoringBatteryOptimizations(packageName), "설정") { askBatteryExemption() },
            Step("명함 이미지", "보낼 명함 사진을 고릅니다", prefs.hasCard, "선택") { pickCard() },
            Step("보낼 문구", "예시 문구 자동 생성으로 쉽게 만들 수 있어요", prefs.intro.isNotBlank(), "작성") {
                selectTab(0); v<EditText>(R.id.etIntro).requestFocus()
            },
            Step("테스트 발송", "내 번호로 보내서 받아 봅니다", prefs.testDone, "보내기") {
                selectTab(1)
                v<ScrollView>(R.id.scroll).post {
                    val content = v<View>(R.id.pageSend).parent as View
                    v<ScrollView>(R.id.scroll).smoothScrollTo(0, content.top + v<View>(R.id.pageSend).top + v<View>(R.id.cardTest).top)
                    v<EditText>(R.id.etTest).requestFocus()
                }
            },
        )
    }

    private fun setupDoneCount() = steps().count { it.done }

    private fun refreshSetup() {
        val list = steps()
        val done = list.count { it.done }
        val allDone = done == list.size
        v<View>(R.id.cardSetup).visibility = if (!allDone || setupExpanded) View.VISIBLE else View.GONE
        v<View>(R.id.tvSetupDone).visibility = if (allDone && !setupExpanded) View.VISIBLE else View.GONE
        v<View>(R.id.tvSetupDone).setOnClickListener { setupExpanded = true; refreshSetup() }
        v<TextView>(R.id.tvSetupProgress).text = "$done / ${list.size}"

        val box = v<LinearLayout>(R.id.setupSteps)
        box.removeAllViews()
        list.forEachIndexed { i, s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(60)
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(TextView(this).apply {
                text = if (s.done) "✓" else "${i + 1}"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (s.done) color(R.color.navy) else color(R.color.text_3))
                background = ContextCompat.getDrawable(this@MainActivity, if (s.done) R.drawable.bg_step_done else R.drawable.bg_step_todo)
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                addView(TextView(this@MainActivity).apply {
                    text = s.title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(if (s.done) R.color.text_3 else R.color.text_1))
                })
                addView(TextView(this@MainActivity).apply {
                    text = s.desc
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(color(R.color.text_3))
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (!s.done) {
                row.addView(Button(this, null, 0, R.style.Btn_Small).apply {
                    text = s.action
                    setOnClickListener { s.onClick() }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
            } else {
                row.addView(TextView(this).apply {
                    text = "완료"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(R.color.mint_text))
                })
            }
            box.addView(row)
        }
        if (allDone && setupExpanded) {
            box.addView(TextView(this).apply {
                text = "접기"
                gravity = Gravity.CENTER
                minHeight = dp(44)
                setTextColor(color(R.color.text_2))
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener { setupExpanded = false; refreshSetup() }
            })
        }
    }

    // ------------------------------------------------------------------ 탭

    private fun setupTabs() {
        listOf(R.id.tabCard, R.id.tabSend, R.id.tabLog).forEachIndexed { i, id ->
            v<View>(id).setOnClickListener { selectTab(i) }
        }
        selectTab(tab)
    }

    private fun selectTab(i: Int) {
        tab = i
        listOf(R.id.tabCard, R.id.tabSend, R.id.tabLog).forEachIndexed { idx, id ->
            v<TextView>(id).apply { isSelected = idx == i; setTypeface(null, if (idx == i) Typeface.BOLD else Typeface.NORMAL) }
        }
        v<View>(R.id.pageCard).visibility = if (i == 0) View.VISIBLE else View.GONE
        v<View>(R.id.pageSend).visibility = if (i == 1) View.VISIBLE else View.GONE
        v<View>(R.id.pageLog).visibility = if (i == 2) View.VISIBLE else View.GONE
        if (i == 2) refreshLog()
    }

    // ------------------------------------------------------------------ 탭 1: 명함·문구

    private fun setupCardPage() {
        v<View>(R.id.btnPick).setOnClickListener { pickCard() }
        v<View>(R.id.btnTemplate).setOnClickListener { showTemplateDialog() }

        val subject = v<EditText>(R.id.etSubject)
        val intro = v<EditText>(R.id.etIntro)
        val missed = v<EditText>(R.id.etMissedIntro)
        subject.setText(prefs.subject)
        intro.setText(prefs.intro)
        missed.setText(prefs.missedIntro)
        // 자동 저장
        subject.afterChange { prefs.subject = it.trim(); refreshPreview() }
        intro.afterChange { prefs.intro = it.trim(); refreshCounts(); refreshPreview() }
        missed.afterChange { prefs.missedIntro = it.trim(); refreshCounts(); refreshPreview() }
        val noAnswer = v<EditText>(R.id.etNoAnswerIntro)
        noAnswer.setText(prefs.noAnswerIntro)
        noAnswer.afterChange { prefs.noAnswerIntro = it.trim(); refreshCounts(); refreshPreview() }

        v<View>(R.id.pvBasic).setOnClickListener { previewType = MsgType.BASIC; refreshPreview() }
        v<View>(R.id.pvMissed).setOnClickListener { previewType = MsgType.MISSED; refreshPreview() }
        v<View>(R.id.pvNoAnswer).setOnClickListener { previewType = MsgType.NO_ANSWER; refreshPreview() }
    }

    private fun pickCard() =
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    private fun refreshCard() {
        val img = v<ImageView>(R.id.imgCard)
        if (prefs.hasCard) {
            img.setImageBitmap(BitmapFactory.decodeFile(prefs.cardFile.absolutePath))
            v<View>(R.id.tvCardEmpty).visibility = View.GONE
            v<Button>(R.id.btnPick).text = "다른 이미지로 바꾸기"
        } else {
            img.setImageDrawable(null)
            v<View>(R.id.tvCardEmpty).visibility = View.VISIBLE
            v<Button>(R.id.btnPick).text = "명함 이미지 선택"
        }
    }

    private fun refreshCounts() {
        v<TextView>(R.id.tvIntroCount).text = "${v<EditText>(R.id.etIntro).length()} / 1000"
        v<TextView>(R.id.tvMissedCount).text = "${v<EditText>(R.id.etMissedIntro).length()} / 1000"
        v<TextView>(R.id.tvNoAnswerCount).text = "${v<EditText>(R.id.etNoAnswerIntro).length()} / 1000"
    }

    private fun refreshPreview() {
        mapOf(R.id.pvBasic to MsgType.BASIC, R.id.pvMissed to MsgType.MISSED, R.id.pvNoAnswer to MsgType.NO_ANSWER).forEach { (id, t) ->
            v<TextView>(id).apply { isSelected = previewType == t; setTypeface(null, if (previewType == t) Typeface.BOLD else Typeface.NORMAL) }
        }
        val img = v<ImageView>(R.id.pvImage)
        if (prefs.hasCard) {
            img.visibility = View.VISIBLE
            img.setImageBitmap(BitmapFactory.decodeFile(prefs.cardFile.absolutePath))
        } else img.visibility = View.GONE
        val subj = prefs.subject
        v<TextView>(R.id.pvSubject).apply { text = subj; visibility = if (subj.isBlank()) View.GONE else View.VISIBLE }
        val body = prefs.introFor(previewType)
        v<TextView>(R.id.pvText).apply {
            if (body.isBlank()) { text = "문구를 입력하면 여기에 보입니다."; setTextColor(color(R.color.text_3)) }
            else { text = body; setTextColor(color(R.color.text_1)) }
        }
    }

    // ------------------------------------------------------------------ 탭 2: 발송 설정

    private fun setupSendPage() {
        v<RadioButton>(R.id.rbConfirm).text = twoLine("확인 후 발송 (권장)", "통화 후 \"명함을 보낼까요?\" 알림이 뜨고, [보내기]를 눌러야 발송됩니다.")
        v<RadioButton>(R.id.rbAuto).text = twoLine("확인 없이 자동 발송", "통화가 끝나면 바로 보냅니다. 문구를 충분히 확인한 뒤 사용하세요.")
        v<RadioGroup>(R.id.rgMode).check(if (prefs.autoSend) R.id.rbAuto else R.id.rbConfirm)
        v<RadioGroup>(R.id.rgMode).setOnCheckedChangeListener { _, id -> prefs.autoSend = id == R.id.rbAuto }

        v<CheckBox>(R.id.cbAnswered).apply { isChecked = prefs.onAnswered; setOnCheckedChangeListener { _, b -> prefs.onAnswered = b } }
        v<CheckBox>(R.id.cbMissed).apply { isChecked = prefs.onMissed; setOnCheckedChangeListener { _, b -> prefs.onMissed = b } }
        v<CheckBox>(R.id.cbNoAnswer).apply {
            text = twoLine("내가 건 전화를 상대가 받지 않았을 때", "발신 후 연결되지 않으면 '연결 안 됨' 문구를 보냅니다.")
            isChecked = prefs.onNoAnswer
            setOnCheckedChangeListener { _, b -> prefs.onNoAnswer = b }
        }
        v<CheckBox>(R.id.cbRejected).apply {
            text = twoLine("거절한 전화도 부재중으로 보기", "통화 화면에서 [거절]을 누른 전화에도 부재중 문구를 보냅니다.")
            isChecked = prefs.onRejected
            setOnCheckedChangeListener { _, b -> prefs.onRejected = b }
        }
        v<CheckBox>(R.id.cbMobile).apply { isChecked = prefs.mobileOnly; setOnCheckedChangeListener { _, b -> prefs.mobileOnly = b } }

        v<EditText>(R.id.etTest).setText(prefs.lastTestNumber)
        v<View>(R.id.btnTest).setOnClickListener { testSend(MsgType.BASIC) }
        v<View>(R.id.btnTestMissed).setOnClickListener { testSend(MsgType.MISSED) }
        v<View>(R.id.btnTestNoAnswer).setOnClickListener { testSend(MsgType.NO_ANSWER) }
    }

    private fun testSend(type: MsgType) {
        val n = Phone.normalize(v<EditText>(R.id.etTest).text.toString())
        if (n.isEmpty()) { toast("받을 번호를 입력해 주세요."); return }
        if (!allGranted()) { permLauncher.launch(required); return }
        prefs.lastTestNumber = n
        testStartedAt = System.currentTimeMillis()
        showTestResult("⏳ 보내는 중… 결과가 몇 초 안에 여기에 표시됩니다.", R.color.amber, R.color.amber_bg)
        if (!MmsSender.send(this, n, force = true, type = type)) refreshTestResult()
    }

    private fun refreshTestResult() {
        if (testStartedAt == 0L) return
        // 테스트 발송으로 남은 기록만 봅니다 (그사이 들어온 실제 통화 기록은 제외)
        val e = prefs.logEntries().firstOrNull { it.test && it.ts >= testStartedAt && it.kind != Prefs.Kind.INFO } ?: return
        when (e.kind) {
            Prefs.Kind.SENT -> showTestResult("✅ 발송 완료 — 휴대폰에 문자가 왔는지 확인하세요.", R.color.mint_text, R.color.mint_bg)
            Prefs.Kind.FAIL -> showTestResult("❌ ${e.message}\n모바일 데이터가 켜져 있는지, 기본 문자 앱으로 MMS가 보내지는지 확인해 주세요.", R.color.red, R.color.red_bg)
            else -> showTestResult("⚠️ ${e.message}", R.color.amber, R.color.amber_bg)
        }
    }

    private fun showTestResult(msg: String, fg: Int, bg: Int) {
        v<TextView>(R.id.tvTestResult).apply {
            visibility = View.VISIBLE
            text = msg
            setTextColor(color(fg))
            backgroundTintList = ColorStateList.valueOf(color(bg))
        }
    }

    // ------------------------------------------------------------------ 탭 3: 기록

    private fun setupLogPage() {
        listOf(R.id.fltAll, R.id.fltSent, R.id.fltSkip).forEachIndexed { i, id ->
            v<View>(id).setOnClickListener { logFilter = i; refreshLog() }
        }
    }

    private fun refreshLog() {
        listOf(R.id.fltAll, R.id.fltSent, R.id.fltSkip).forEachIndexed { i, id ->
            v<TextView>(id).apply { isSelected = i == logFilter; setTypeface(null, if (i == logFilter) Typeface.BOLD else Typeface.NORMAL) }
        }
        val entries = prefs.logEntries().filter {
            when (logFilter) {
                1 -> it.kind == Prefs.Kind.SENT || it.kind == Prefs.Kind.FAIL
                2 -> it.kind == Prefs.Kind.SKIP
                else -> true
            }
        }
        val list = v<LinearLayout>(R.id.logList)
        list.removeAllViews()
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "아직 기록이 없습니다."
                setTextColor(color(R.color.text_3))
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
            return
        }
        entries.forEachIndexed { i, e ->
            if (i > 0) list.addView(View(this).apply { setBackgroundColor(color(R.color.line)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            val (label, fg, bg) = when (e.kind) {
                Prefs.Kind.SENT -> Triple("보냄", R.color.mint_text, R.color.mint_bg)
                Prefs.Kind.FAIL -> Triple("실패", R.color.red, R.color.red_bg)
                Prefs.Kind.SKIP -> Triple("안 보냄", R.color.text_2, R.color.gray_bg)
                Prefs.Kind.INFO -> Triple("진행", R.color.amber, R.color.amber_bg)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, dp(12))
            }
            row.addView(TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(fg))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_badge)
                backgroundTintList = ColorStateList.valueOf(color(bg))
                setPadding(dp(8), dp(3), dp(8), dp(3))
                isSingleLine = true
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                width = dp(64)
            })
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = e.message
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(color(R.color.text_1))
                })
                addView(TextView(this@MainActivity).apply {
                    text = "${e.time}  ·  ${e.number}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(color(R.color.text_3))
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row)
        }
    }

    // ------------------------------------------------------------------ 새로고침

    private fun refreshAll() {
        refreshStatus()
        refreshSetup()
        refreshCard()
        refreshCounts()
        refreshPreview()
        if (tab == 2) refreshLog()
        refreshTestResult()
    }

    /** 다른 곳(통화 처리·발송 결과)에서 값이 바뀌었을 때: 입력칸은 건드리지 않음 */
    private fun refreshLive() {
        refreshStatus()
        refreshSetup()
        if (tab == 2) refreshLog()
        refreshTestResult()
    }

    // ------------------------------------------------------------------ 권한·배터리

    private fun allGranted() = required.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionHelp() {
        AlertDialog.Builder(this)
            .setTitle("권한이 아직 허용되지 않았어요")
            .setMessage(
                "앱 설정 화면에서 직접 허용해 주세요.\n\n" +
                "1. [설정 열기] → 권한 → 전화·통화 기록·SMS·알림을 '허용'\n" +
                "2. 허용 버튼이 회색으로 막혀 있으면: 같은 화면 우측 상단 ⋮ → '제한된 설정 허용'을 먼저 누르세요."
            )
            .setNegativeButton("닫기", null)
            .setPositiveButton("설정 열기") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .show()
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ------------------------------------------------------------------ 예시 문구 자동 생성

    private fun showTemplateDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), dp(4)) }
        val fields = listOf(
            Triple("org", "회사·사무실명", "예: 홈즈 ○○점"),
            Triple("name", "이름", "예: 홍길동"),
            Triple("title", "직함", "예: 매니저"),
            Triple("phone", "사무실 번호", "예: 02-000-0000"),
            Triple("address", "주소", "예: 서울시 ○○구 ○○로 00"),
            Triple("hours", "운영시간", "예: 평일 09:00~18:00"),
        )
        val inputs = fields.associate { (key, label, hint) ->
            box.addView(TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.text_1))
                setPadding(0, dp(12), 0, dp(6))
            })
            key to EditText(this).apply {
                this.hint = hint
                setText(prefs.getField(key))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                inputType = if (key == "phone") InputType.TYPE_CLASS_PHONE else InputType.TYPE_CLASS_TEXT
                box.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        box.addView(TextView(this).apply {
            text = "말투"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text_1))
            setPadding(0, dp(16), 0, dp(4))
        })
        val tones = Templates.Tone.values()
        val toneDesc = mapOf(
            Templates.Tone.POLITE to "격식 있는 존댓말",
            Templates.Tone.FRIENDLY to "부드럽고 친근하게",
            Templates.Tone.SHORT to "핵심만 짧게",
        )
        val group = RadioGroup(this)
        tones.forEachIndexed { idx, t ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = idx
                text = twoLine(t.label, toneDesc[t] ?: "")
                buttonTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.choice_tint)
                minHeight = dp(48)
            })
        }
        group.check(group.getChildAt(0).id)
        box.addView(group)
        box.addView(TextView(this).apply {
            text = "회사명이나 이름 중 하나는 꼭 입력해 주세요."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(color(R.color.text_3))
            setPadding(0, dp(8), 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("예시 문구 자동 생성")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("취소", null)
            .setPositiveButton("만들기") { _, _ ->
                inputs.forEach { (k, e) -> prefs.setField(k, e.text.toString().trim()) }
                val info = Templates.Info(
                    org = prefs.getField("org"), name = prefs.getField("name"), title = prefs.getField("title"),
                    phone = prefs.getField("phone"), address = prefs.getField("address"), hours = prefs.getField("hours"),
                )
                if (info.org.isBlank() && info.name.isBlank()) {
                    toast("회사명이나 이름 중 하나는 입력해 주세요."); return@setPositiveButton
                }
                val tone = tones[group.findViewById<View>(group.checkedRadioButtonId).tag as Int]
                val r = Templates.generate(info, tone)
                val apply = {
                    v<EditText>(R.id.etSubject).setText(r.subject)
                    v<EditText>(R.id.etIntro).setText(r.intro)
                    v<EditText>(R.id.etMissedIntro).setText(r.missed)
                    v<EditText>(R.id.etNoAnswerIntro).setText(r.noAnswer)
                    selectTab(0)
                    toast("예시 문구를 넣었습니다. 미리보기로 확인하고 필요하면 고쳐 주세요.")
                }
                val hasText = listOf(R.id.etIntro, R.id.etMissedIntro, R.id.etNoAnswerIntro).any { v<EditText>(it).text.isNotBlank() }
                if (!hasText) apply()
                else AlertDialog.Builder(this)
                    .setMessage("지금 입력된 문구를 예시 문구로 바꿀까요?")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("바꾸기") { _, _ -> apply() }
                    .show()
            }
            .show()
    }

    // ------------------------------------------------------------------ 앱 업데이트

    private fun checkUpdate(manual: Boolean) {
        if (!Updater.configured) return
        if (manual) toast("새 버전을 확인하는 중…")
        Thread {
            val r = Updater.newerRelease()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (r == null) { if (manual) toast("최신 버전입니다."); return@runOnUiThread }
                AlertDialog.Builder(this)
                    .setTitle("새 버전이 있습니다 (v1.${r.versionCode})")
                    .setMessage(r.notes.ifBlank { "업데이트하시겠습니까?" }.take(600))
                    .setNegativeButton("나중에", null)
                    .setPositiveButton("업데이트") { _, _ -> downloadAndInstall(r) }
                    .show()
            }
        }.start()
    }

    private fun downloadAndInstall(r: Updater.Release) {
        if (!Updater.canInstall(this)) {
            toast("'이 출처의 앱 설치 허용'을 켜고 돌아와 다시 눌러 주세요.")
            Updater.openInstallPermissionSettings(this); return
        }
        toast("내려받는 중…")
        Thread {
            try {
                val apk = Updater.download(this, r)
                runOnUiThread { Updater.install(this, apk) }
            } catch (e: Exception) {
                runOnUiThread { toast("업데이트 다운로드 실패: ${e.message}") }
            }
        }.start()
    }

    // ------------------------------------------------------------------ 명함 이미지 저장

    /** 선택한 이미지를 MMS 용량(약 300KB 이하)에 맞게 줄여 저장합니다. */
    private fun saveCard(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
            val src = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return toast("이미지를 열 수 없습니다.")

            var maxSide = 1280
            var bytes: ByteArray
            while (true) {
                val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
                val bmp = Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
                var q = 90
                do {
                    bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
                    q -= 10
                } while (bytes.size > 280_000 && q >= 50)
                if (bytes.size <= 280_000 || maxSide <= 480) break
                maxSide -= 200
            }
            prefs.cardFile.writeBytes(bytes)
            toast("명함 이미지를 저장했습니다 (${bytes.size / 1024}KB).")
            refreshAll()
        } catch (e: Exception) {
            toast("이미지 저장 실패: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ 도우미

    private fun twoLine(title: String, desc: String): CharSequence {
        val sb = SpannableStringBuilder(title)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(color(R.color.text_1)), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(AbsoluteSizeSpan(16, true), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (desc.isNotEmpty()) {
            val start = sb.length + 1
            sb.append("\n").append(desc)
            sb.setSpan(ForegroundColorSpan(color(R.color.text_2)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(AbsoluteSizeSpan(13, true), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun EditText.afterChange(block: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = block(s?.toString() ?: "")
    })

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun <T : View> v(id: Int): T = findViewById(id)

    companion object { private const val STEPS = 5 }
}
