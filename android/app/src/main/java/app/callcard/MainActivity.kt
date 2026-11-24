package app.callcard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    private val required: Array<String>
        get() = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.SEND_SMS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { saveCard(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        Notifier.createChannels(this)

        v<Switch>(R.id.swEnabled).isChecked = prefs.enabled
        v<EditText>(R.id.etSubject).setText(prefs.subject)
        v<EditText>(R.id.etIntro).setText(prefs.intro)
        v<EditText>(R.id.etMissedIntro).setText(prefs.missedIntro)
        v<CheckBox>(R.id.cbAnswered).isChecked = prefs.onAnswered
        v<CheckBox>(R.id.cbMissed).isChecked = prefs.onMissed
        v<CheckBox>(R.id.cbMobile).isChecked = prefs.mobileOnly
        v<RadioGroup>(R.id.rgMode).check(if (prefs.autoSend) R.id.rbAuto else R.id.rbConfirm)

        v<TextView>(R.id.tvVersion).text = "콜명함 v${BuildConfig.VERSION_NAME}"
        v<Button>(R.id.btnUpdate).isEnabled = Updater.configured
        v<Button>(R.id.btnUpdate).setOnClickListener { checkUpdate(manual = true) }
        v<Button>(R.id.btnTemplate).setOnClickListener { showTemplateDialog() }
        UpdateCheckWorker.schedule(this)
        if (savedInstanceState == null) checkUpdate(manual = intent.getBooleanExtra("checkUpdate", false))

        v<Button>(R.id.btnPerm).setOnClickListener { permLauncher.launch(required) }
        v<Button>(R.id.btnBattery).setOnClickListener { askBatteryExemption() }
        v<Button>(R.id.btnPick).setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        // 발송 방식을 바꾸면 바로 저장 (저장 버튼을 안 눌러도 적용)
        v<RadioGroup>(R.id.rgMode).setOnCheckedChangeListener { _, id -> prefs.autoSend = id == R.id.rbAuto }
        v<CheckBox>(R.id.cbAnswered).setOnCheckedChangeListener { _, b -> prefs.onAnswered = b }
        v<CheckBox>(R.id.cbMissed).setOnCheckedChangeListener { _, b -> prefs.onMissed = b }
        v<CheckBox>(R.id.cbMobile).setOnCheckedChangeListener { _, b -> prefs.mobileOnly = b }
        v<Switch>(R.id.swEnabled).setOnCheckedChangeListener { sw, on ->
            if (on && !allGranted()) {
                sw.isChecked = false
                toast("먼저 권한을 모두 허용해 주세요.")
                permLauncher.launch(required)
            } else {
                prefs.enabled = on
            }
        }
        v<Button>(R.id.btnSave).setOnClickListener { save(); toast("저장했습니다.") }
        v<Button>(R.id.btnTest).setOnClickListener { testSend(missed = false) }
        v<Button>(R.id.btnTestMissed).setOnClickListener { testSend(missed = true) }
    }

    // ---------- 예시 문구 자동 생성 ----------
    private fun showTemplateDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val fields = listOf(
            "org" to "회사·사무실명 (예: 홈즈 ○○점)",
            "name" to "이름",
            "title" to "직함 (예: 매니저)",
            "phone" to "사무실 번호",
            "address" to "주소",
            "hours" to "운영시간 (예: 평일 09:00~18:00)",
        )
        val inputs = fields.associate { (key, hint) ->
            key to EditText(this).apply {
                this.hint = hint
                setText(prefs.getField(key))
                inputType = if (key == "phone") android.text.InputType.TYPE_CLASS_PHONE
                            else android.text.InputType.TYPE_CLASS_TEXT
                box.addView(this)
            }
        }
        val tones = Templates.Tone.values()
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        tones.forEachIndexed { idx, t ->
            group.addView(RadioButton(this).apply { id = 7000 + idx; text = t.label })
        }
        group.check(7000)
        box.addView(group)

        android.app.AlertDialog.Builder(this)
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
                val r = Templates.generate(info, tones[group.checkedRadioButtonId - 7000])
                val apply = {
                    v<EditText>(R.id.etSubject).setText(r.subject)
                    v<EditText>(R.id.etIntro).setText(r.intro)
                    v<EditText>(R.id.etMissedIntro).setText(r.missed)
                    toast("예시 문구를 넣었습니다. 확인·수정 후 [저장]을 눌러 주세요.")
                }
                val hasText = v<EditText>(R.id.etIntro).text.isNotBlank() || v<EditText>(R.id.etMissedIntro).text.isNotBlank()
                if (!hasText) apply()
                else android.app.AlertDialog.Builder(this)
                    .setMessage("지금 입력된 소개글을 예시 문구로 바꿀까요?")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("바꾸기") { _, _ -> apply() }
                    .show()
            }
            .show()
    }

    // ---------- 앱 업데이트 ----------
    private fun checkUpdate(manual: Boolean) {
        if (!Updater.configured) return
        if (manual) toast("새 버전을 확인하는 중…")
        Thread {
            val r = Updater.newerRelease()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (r == null) { if (manual) toast("최신 버전입니다."); return@runOnUiThread }
                android.app.AlertDialog.Builder(this)
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

    private fun testSend(missed: Boolean) {
        save()
        val n = v<EditText>(R.id.etTest).text.toString()
        if (!allGranted()) { permLauncher.launch(required); return }
        if (MmsSender.send(this, n, force = true, missed = missed)) toast("발송을 요청했습니다.")
        else toast("발송하지 못했습니다. 기록을 확인하세요.")
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun save() {
        prefs.subject = v<EditText>(R.id.etSubject).text.toString().trim()
        prefs.intro = v<EditText>(R.id.etIntro).text.toString().trim()
        prefs.missedIntro = v<EditText>(R.id.etMissedIntro).text.toString().trim()
        prefs.onAnswered = v<CheckBox>(R.id.cbAnswered).isChecked
        prefs.onMissed = v<CheckBox>(R.id.cbMissed).isChecked
        prefs.mobileOnly = v<CheckBox>(R.id.cbMobile).isChecked
        prefs.autoSend = v<RadioGroup>(R.id.rgMode).checkedRadioButtonId == R.id.rbAuto
    }

    private fun refresh() {
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        v<TextView>(R.id.permStatus).text =
            if (missing.isEmpty()) "✅ 필요한 권한이 모두 허용되었습니다."
            else "⚠️ 허용되지 않은 권한이 있습니다. [권한 허용]을 눌러 주세요.\n" +
                "버튼이 회색으로 막혀 있으면: 설정 › 애플리케이션 › 콜명함 › 우측 상단 ⋮ › '제한된 설정 허용'"
        v<Button>(R.id.btnPerm).isEnabled = missing.isNotEmpty()
        if (missing.isNotEmpty() && prefs.enabled) {
            prefs.enabled = false; v<Switch>(R.id.swEnabled).isChecked = false
        }
        val pm = getSystemService(PowerManager::class.java)
        v<Button>(R.id.btnBattery).isEnabled = !pm.isIgnoringBatteryOptimizations(packageName)

        val img = v<ImageView>(R.id.imgCard)
        if (prefs.hasCard) img.setImageBitmap(BitmapFactory.decodeFile(prefs.cardFile.absolutePath))
        val lines = prefs.logLines()
        v<TextView>(R.id.tvLog).text = if (lines.isEmpty()) "아직 기록이 없습니다." else lines.joinToString("\n")
    }

    private fun allGranted() = required.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

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
            refresh()
        } catch (e: Exception) {
            toast("이미지 저장 실패: ${e.message}")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun <T : android.view.View> v(id: Int): T = findViewById(id)
}
