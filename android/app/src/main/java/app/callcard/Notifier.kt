package app.callcard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    const val ACTION_SEND = "app.callcard.SEND"
    const val ACTION_SKIP = "app.callcard.SKIP"
    private const val CH_CONFIRM = "confirm"
    private const val CH_RESULT = "result"

    fun idFor(number: String) = number.hashCode()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_CONFIRM, "발송 확인", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, "발송 결과", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun showConfirm(context: Context, number: String, type: MsgType): Boolean {
        val label = when (type) { MsgType.MISSED -> "부재중"; MsgType.NO_ANSWER -> "상대가 받지 않음"; MsgType.BASIC -> "통화 종료" }
        val send = action(context, ACTION_SEND, number, type)
        val skip = action(context, ACTION_SKIP, number, type)
        val n = NotificationCompat.Builder(context, CH_CONFIRM)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("명함을 보낼까요? ($label)")
            .setContentText(formatted(number))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFF0A213B.toInt())
            .setAutoCancel(true)
            .addAction(0, "보내기", send)
            .addAction(0, "안 보내기", skip)
            .build()
        return notify(context, idFor(number), n)
    }

    fun showResult(context: Context, number: String, ok: Boolean) {
        val n = NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (ok) "명함 발송 완료" else "명함 발송 실패")
            .setContentText(formatted(number))
            .setAutoCancel(true)
            .build()
        notify(context, idFor(number) + 1, n)
    }

    /** 설치 허용이 없어 자동 업데이트를 못 할 때 */
    fun showUpdate(context: Context, versionName: String, needsPermission: Boolean = false) {
        val open = PendingIntent.getActivity(
            context, 9001,
            Intent(context, MainActivity::class.java).putExtra("checkUpdate", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("콜명함 $versionName 업데이트가 있습니다")
            .setContentText(if (needsPermission) "자동 업데이트를 위해 '이 출처의 앱 설치'를 허용해 주세요" else "눌러서 설치하세요")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notify(context, 9001, n)
    }

    /** 처음 한 번은 Android가 설치 확인을 요구합니다 */
    fun showInstallConfirm(context: Context, versionName: String, confirm: Intent) {
        val pi = PendingIntent.getActivity(context, 9002, confirm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("콜명함 $versionName 설치 준비 완료")
            .setContentText("눌러서 [업데이트]를 한 번만 눌러 주세요. 다음부터는 자동으로 설치됩니다.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        notify(context, 9002, n)
    }

    fun showUpdated(context: Context, versionName: String) {
        val n = NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("콜명함이 v$versionName(으)로 업데이트되었습니다")
            .setContentText("설정과 문구는 그대로입니다.")
            .setAutoCancel(true)
            .build()
        notify(context, 9003, n)
    }

    private fun action(context: Context, act: String, number: String, type: MsgType): PendingIntent =
        PendingIntent.getBroadcast(
            context, (act + number).hashCode(),
            Intent(context, ConfirmActionReceiver::class.java).setAction(act).putExtra("number", number).putExtra("type", type.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun formatted(n: String) =
        if (n.length == 11) "${n.take(3)}-${n.substring(3, 7)}-${n.takeLast(4)}" else n

    private fun notify(context: Context, id: Int, n: android.app.Notification): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < 33) {
            createChannels(context)
            NotificationManagerCompat.from(context).notify(id, n)
            return true
        }
        return false
    }
}
