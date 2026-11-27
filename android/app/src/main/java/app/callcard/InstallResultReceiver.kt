package app.callcard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** 업데이트 설치 결과 처리: 확인이 필요하면 설치 화면을 띄우고, 백그라운드면 알림으로 알립니다. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getIntExtra("versionCode", 0)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (MainActivity.visible) {
                    context.startActivity(confirm)
                } else {
                    Notifier.showInstallConfirm(context, "v1.$code", confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // 설치되면 앱이 재시작되고 UpdatedReceiver가 알립니다.
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "알 수 없는 오류"
                Prefs(context).addLog("", "업데이트 설치 실패: $msg", Prefs.Kind.FAIL)
            }
        }
    }
}
