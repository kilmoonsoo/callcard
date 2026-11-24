package app.callcard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** 전화 상태가 '대기(IDLE)'로 돌아오면(통화 종료·부재중) 통화 기록 확인 작업을 예약합니다. */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!Prefs(context).enabled) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        if (state != TelephonyManager.EXTRA_STATE_IDLE) return

        // 통화 기록이 저장될 때까지 몇 초 기다린 뒤 확인합니다.
        val work = OneTimeWorkRequestBuilder<CallLogWorker>()
            .setInitialDelay(4, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("check-call-log", ExistingWorkPolicy.REPLACE, work)
    }
}
