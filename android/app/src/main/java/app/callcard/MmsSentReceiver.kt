package app.callcard

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/** 통신사 전송 결과를 받아 기록하고, 실패하면 알립니다. */
class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra("number") ?: return
        intent.getStringExtra("file")?.let { File(it).delete() }
        val prefs = Prefs(context)
        if (resultCode == Activity.RESULT_OK) {
            val test = intent.getBooleanExtra("test", false)
            prefs.addLog(number, if (test) "발송 완료 (테스트)" else "발송 완료", Prefs.Kind.SENT)
            if (test) prefs.testDone = true
        } else {
            prefs.unmarkSentToday(number) // 실패 시 다음 통화 때 다시 보낼 수 있게
            prefs.addLog(number, "발송 실패 (통신사 응답 코드 $resultCode)", Prefs.Kind.FAIL)
            Notifier.showResult(context, number, false)
        }
    }
}
