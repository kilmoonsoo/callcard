package app.callcard

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import java.io.File

object MmsSender {

    /**
     * 명함 이미지 + 소개글을 MMS로 보냅니다.
     * @param force true면 '하루 1회' 제한을 무시합니다(테스트 발송용).
     * @param missed true면 부재중 전화용 소개글을 보냅니다.
     * @return 발송 요청 여부
     */
    fun send(context: Context, rawNumber: String, force: Boolean = false, missed: Boolean = false): Boolean {
        val prefs = Prefs(context)
        val number = Phone.normalize(rawNumber)
        if (number.isEmpty()) return false
        if (!force && prefs.alreadySentToday(number)) {
            prefs.addLog(number, "건너뜀(오늘 이미 발송)")
            return false
        }
        val text = prefs.introFor(missed)
        if (!prefs.hasCard && text.isBlank()) {
            prefs.addLog(number, "건너뜀(명함·소개글 미설정)")
            return false
        }

        return try {
            val jpeg = if (prefs.hasCard) prefs.cardFile.readBytes() else null
            val pdu = MmsPdu.build(number, prefs.subject, text, jpeg)

            val dir = File(context.cacheDir, "mms").apply { mkdirs() }
            val file = File(dir, "send_${System.currentTimeMillis()}.pdu")
            file.writeBytes(pdu)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.mms", file)
            for (pkg in listOf("com.android.phone", "com.android.mms.service")) {
                try {
                    context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { }
            }

            val sentIntent = PendingIntent.getBroadcast(
                context,
                file.name.hashCode(),
                Intent(context, MmsSentReceiver::class.java)
                    .putExtra("number", number)
                    .putExtra("file", file.absolutePath),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            prefs.markSentToday(number) // 같은 통화로 중복 발송되지 않도록 먼저 표시
            smsManager(context).sendMultimediaMessage(context, uri, null, null, sentIntent)
            prefs.addLog(number, if (missed) "발송 요청(부재중)" else "발송 요청")
            true
        } catch (e: Exception) {
            prefs.unmarkSentToday(number)
            prefs.addLog(number, "실패: ${e.javaClass.simpleName}")
            Notifier.showResult(context, number, false)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()
}
