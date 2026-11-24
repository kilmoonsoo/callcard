package app.callcard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 전화 상태가 '대기(IDLE)'로 돌아오면(통화 종료·부재중) 통화 기록을 확인합니다.
 * 절전 정책에 밀리지 않도록 수신 직후 바로 처리하고, 놓친 경우를 위해 예비 작업도 예약합니다.
 */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!Prefs(context).enabled) return
        if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_IDLE) return

        val app = context.applicationContext
        // 예비: 바로 처리가 실패해도 잠시 뒤 한 번 더 확인
        WorkManager.getInstance(app).enqueueUniqueWork(
            "check-call-log", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CallLogWorker>().setInitialDelay(20, TimeUnit.SECONDS).build()
        )

        val pending = goAsync()
        Thread {
            try {
                synchronized(CallChecker) {
                    // 통화 기록이 저장될 때까지 기다렸다가 확인 (최대 약 8초)
                    for (wait in longArrayOf(2500, 2500, 3000)) {
                        Thread.sleep(wait)
                        if (CallChecker.check(app)) break
                    }
                }
            } catch (e: Exception) {
                Prefs(app).addLog("", "오류: ${e.javaClass.simpleName}", Prefs.Kind.FAIL)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
