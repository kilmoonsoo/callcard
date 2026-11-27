package app.callcard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 앱이 새 버전으로 바뀐 직후: 알림을 띄우고 업데이트 확인 일정을 다시 잡습니다. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Prefs(context).addLog("", "앱이 v${BuildConfig.VERSION_NAME}(으)로 자동 업데이트됨")
        Notifier.showUpdated(context, BuildConfig.VERSION_NAME)
        UpdateCheckWorker.schedule(context)
    }
}
