package app.callcard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat

/** 가장 최근 통화 기록을 보고 발송 대상이면 자동 발송하거나 확인 알림을 띄웁니다. 건너뛴 이유는 기록에 남깁니다. */
object CallChecker {

    /** @return 새 통화 기록을 찾아 처리했으면 true, 아직 기록이 없으면 false(다시 시도할 것) */
    fun check(ctx: Context): Boolean {
        val prefs = Prefs(ctx)
        if (!prefs.enabled) return true
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) {
            prefs.addLog("", "안 보냄: 통화 기록 권한 없음", Prefs.Kind.SKIP); return true
        }

        val projection = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
        val c = ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC"
        ) ?: run { prefs.addLog("", "안 보냄: 통화 기록을 읽을 수 없음", Prefs.Kind.SKIP); return true }

        c.use {
            if (!it.moveToFirst()) return false
            val id = it.getLong(0)
            val number = Phone.normalize(it.getString(1))
            val type = it.getInt(2)
            val endedAt = it.getLong(3) + it.getLong(4) * 1000

            if (id == prefs.lastHandledCallId) return false // 아직 이번 통화가 기록되지 않음
            // 오래된 기록이면(앱 재시작 등) 무시
            if (System.currentTimeMillis() - endedAt > 5 * 60 * 1000) return false
            prefs.lastHandledCallId = id

            val duration = it.getLong(4)
            val msg: MsgType
            val wanted: Boolean
            when (type) {
                CallLog.Calls.INCOMING_TYPE -> { msg = MsgType.BASIC; wanted = prefs.onAnswered }
                CallLog.Calls.MISSED_TYPE -> { msg = MsgType.MISSED; wanted = prefs.onMissed }
                CallLog.Calls.REJECTED_TYPE -> {
                    if (!prefs.onRejected) {
                        prefs.addLog(number, "안 보냄: 거절한 전화 (발송 설정에서 켤 수 있음)", Prefs.Kind.SKIP); return true
                    }
                    msg = MsgType.MISSED; wanted = prefs.onMissed
                }
                CallLog.Calls.OUTGOING_TYPE -> {
                    if (duration > 0) return true // 내가 건 전화가 연결됨: 보내지 않고 기록도 안 함
                    msg = MsgType.NO_ANSWER; wanted = prefs.onNoAnswer
                }
                CallLog.Calls.BLOCKED_TYPE -> { prefs.addLog(number, "안 보냄: 차단한 번호", Prefs.Kind.SKIP); return true }
                else -> { prefs.addLog(number, "안 보냄: 기타 통화 (유형 $type)", Prefs.Kind.SKIP); return true }
            }
            if (!wanted) {
                val what = when {
                    type == CallLog.Calls.REJECTED_TYPE -> "거절한 전화(부재중)"
                    msg == MsgType.MISSED -> "부재중"
                    msg == MsgType.NO_ANSWER -> "연결 안 된 발신"
                    else -> "받은 전화"
                }
                prefs.addLog(number, "안 보냄: $what 발송이 꺼져 있음", Prefs.Kind.SKIP); return true
            }
            if (number.isEmpty()) { prefs.addLog("", "안 보냄: 발신번호 표시 제한", Prefs.Kind.SKIP); return true }
            if (prefs.mobileOnly && !Phone.isKoreanMobile(number)) { prefs.addLog(number, "안 보냄: 휴대폰 번호가 아님", Prefs.Kind.SKIP); return true }
            if (prefs.alreadySentToday(number)) { prefs.addLog(number, "안 보냄: 오늘 이미 보낸 번호", Prefs.Kind.SKIP); return true }

            if (prefs.autoSend) {
                MmsSender.send(ctx, number, type = msg)
            } else {
                val shown = Notifier.showConfirm(ctx, number, msg)
                if (shown) prefs.addLog(number, "확인 알림을 띄움 — 알림에서 [보내기]를 눌러 주세요")
                else prefs.addLog(number, "안 보냄: 알림 권한이 꺼져 있음 (확인 후 발송 모드)", Prefs.Kind.SKIP)
            }
            return true
        }
    }
}
