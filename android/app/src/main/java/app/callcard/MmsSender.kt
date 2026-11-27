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
     * @param type 기본 / 부재중 / 연결 안 됨 중 보낼 문구
     * @return 발송 요청 여부
     */
    fun send(context: Context, rawNumber: String, force: Boolean = false, type: MsgType = MsgType.BASIC): Boolean {
        val prefs = Prefs(context)
        val number = Phone.normalize(rawNumber)
        if (number.isEmpty()) return false
        if (!force && prefs.alreadySentToday(number)) {
            prefs.addLog(number, "안 보냄: 오늘 이미 보낸 번호", Prefs.Kind.SKIP)
            return false
        }
        val text = prefs.introFor(type)
        if (!prefs.hasCard && text.isBlank()) {
            prefs.addLog(number, "안 보냄: 명함·문구가 비어 있음", Prefs.Kind.SKIP, test = force)
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
                    .putExtra("file", file.absolutePath)
                    .putExtra("test", force),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (!force) prefs.markSentToday(number) // 같은 통화로 중복 발송되지 않도록 먼저 표시 (테스트는 제외)
            smsManager(context).sendMultimediaMessage(context, uri, null, null, sentIntent)
            prefs.addLog(number, "발송 중… (${type.label} 문구)" + if (force) " · 테스트" else "", test = force)
            true
        } catch (e: Exception) {
            if (!force) prefs.unmarkSentToday(number)
            prefs.addLog(number, "발송 실패: ${e.javaClass.simpleName}", Prefs.Kind.FAIL, test = force)
            Notifier.showResult(context, number, false)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()
}
