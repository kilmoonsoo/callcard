package app.callcard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 앱 설정과 발송 기록. 모든 데이터는 이 휴대폰 안에만 저장됩니다. */
class Prefs(context: Context) {
    private val ctx = context.applicationContext
    private val sp = ctx.getSharedPreferences("callcard", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** true = 자동 발송, false = 발송 전 확인 알림 */
    var autoSend: Boolean
        get() = sp.getBoolean("autoSend", false)
        set(v) = sp.edit().putBoolean("autoSend", v).apply()

    var onAnswered: Boolean
        get() = sp.getBoolean("onAnswered", true)
        set(v) = sp.edit().putBoolean("onAnswered", v).apply()

    var onMissed: Boolean
        get() = sp.getBoolean("onMissed", true)
        set(v) = sp.edit().putBoolean("onMissed", v).apply()

    /** 010 등 휴대폰 번호에만 발송 (유선·대표번호는 MMS 수신 불가) */
    var mobileOnly: Boolean
        get() = sp.getBoolean("mobileOnly", true)
        set(v) = sp.edit().putBoolean("mobileOnly", v).apply()

    var subject: String
        get() = sp.getString("subject", "명함 드립니다") ?: ""
        set(v) = sp.edit().putString("subject", v).apply()

    var intro: String
        get() = sp.getString("intro", "") ?: ""
        set(v) = sp.edit().putString("intro", v).apply()

    /** 부재중 전화용 소개글. 비워두면 기본 소개글을 보냅니다. */
    var missedIntro: String
        get() = sp.getString("missedIntro", "") ?: ""
        set(v) = sp.edit().putString("missedIntro", v).apply()

    fun introFor(missed: Boolean): String =
        if (missed && missedIntro.isNotBlank()) missedIntro else intro

    /** 예시 문구 생성 창에 입력했던 값 */
    fun getField(key: String): String = sp.getString("tpl_$key", "") ?: ""
    fun setField(key: String, v: String) = sp.edit().putString("tpl_$key", v).apply()

    var lastHandledCallId: Long
        get() = sp.getLong("lastHandledCallId", -1L)
        set(v) = sp.edit().putLong("lastHandledCallId", v).apply()

    val cardFile: File get() = File(ctx.filesDir, "card.jpg")
    val hasCard: Boolean get() = cardFile.exists() && cardFile.length() > 0

    // ---- 동일 번호 하루 1회 ----
    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date())

    @Synchronized
    fun alreadySentToday(number: String): Boolean {
        val map = JSONObject(sp.getString("sentDay", "{}") ?: "{}")
        return map.optString(number) == today()
    }

    @Synchronized
    fun markSentToday(number: String) {
        val t = today()
        val old = JSONObject(sp.getString("sentDay", "{}") ?: "{}")
        val fresh = JSONObject()
        old.keys().forEach { k -> if (old.optString(k) == t) fresh.put(k, t) } // 지난 날짜는 정리
        fresh.put(number, t)
        sp.edit().putString("sentDay", fresh.toString()).apply()
    }

    @Synchronized
    fun unmarkSentToday(number: String) {
        val map = JSONObject(sp.getString("sentDay", "{}") ?: "{}")
        map.remove(number)
        sp.edit().putString("sentDay", map.toString()).apply()
    }

    // ---- 발송 기록 (최근 30건) ----
    @Synchronized
    fun addLog(number: String, message: String) {
        val arr = JSONArray(sp.getString("log", "[]") ?: "[]")
        val entry = JSONObject()
            .put("t", SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date()))
            .put("n", if (number.isEmpty()) "-" else Phone.mask(number))
            .put("m", message)
        val out = JSONArray().put(entry)
        for (i in 0 until minOf(arr.length(), 49)) out.put(arr.get(i))
        sp.edit().putString("log", out.toString()).apply()
    }

    fun logLines(): List<String> {
        val arr = JSONArray(sp.getString("log", "[]") ?: "[]")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            "${o.optString("t")}  ${o.optString("n")}  ${o.optString("m")}"
        }
    }
}

object Phone {
    /** 숫자만 남기고 +82 를 0 으로 바꿉니다. */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var d = raw.filter { it.isDigit() || it == '+' }
        if (d.startsWith("+82")) d = "0" + d.removePrefix("+82")
        return d.filter { it.isDigit() }
    }

    fun isKoreanMobile(n: String) = Regex("^01[016789]\\d{7,8}$").matches(n)

    fun mask(n: String): String =
        if (n.length >= 8) n.take(3) + "-****-" + n.takeLast(4) else n
}
