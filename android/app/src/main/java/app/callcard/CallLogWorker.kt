package app.callcard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

/** 가장 최근 통화 기록을 보고 발송 대상이면 자동 발송하거나 확인 알림을 띄웁니다. */
class CallLogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        if (!prefs.enabled) return Result.success()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) return Result.success()

        val projection = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC"
        )?.use { c ->
            if (!c.moveToFirst()) return Result.success()
            val id = c.getLong(0)
            val number = Phone.normalize(c.getString(1))
            val type = c.getInt(2)
            val endedAt = c.getLong(3) + c.getLong(4) * 1000

            if (id == prefs.lastHandledCallId) return Result.success()
            prefs.lastHandledCallId = id
            // 오래된 기록이면(앱 재시작 등) 무시
            if (System.currentTimeMillis() - endedAt > 5 * 60 * 1000) return Result.success()

            val wanted = when (type) {
                CallLog.Calls.INCOMING_TYPE -> prefs.onAnswered
                CallLog.Calls.MISSED_TYPE -> prefs.onMissed
                else -> false // 발신·거절·차단 통화는 제외
            }
            if (!wanted || number.isEmpty()) return Result.success()
            if (prefs.mobileOnly && !Phone.isKoreanMobile(number)) return Result.success()
            if (prefs.alreadySentToday(number)) return Result.success()

            val missed = type == CallLog.Calls.MISSED_TYPE
            if (prefs.autoSend) MmsSender.send(ctx, number, missed = missed)
            else Notifier.showConfirm(ctx, number, missed)
        }
        return Result.success()
    }
}
