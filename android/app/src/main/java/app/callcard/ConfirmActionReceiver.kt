package app.callcard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/** 확인 알림의 [보내기]/[안 보내기] 버튼 처리 */
class ConfirmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra("number") ?: return
        NotificationManagerCompat.from(context).cancel(Notifier.idFor(number))
        if (intent.action == Notifier.ACTION_SEND) MmsSender.send(context, number, missed = intent.getBooleanExtra("missed", false))
        else Prefs(context).addLog(number, "안 보냄: 알림에서 [안 보내기] 선택", Prefs.Kind.SKIP)
    }
}
