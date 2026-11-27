package app.callcard

import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageInstaller
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases에서 새 버전을 확인하고 내려받아 설치 화면을 띄웁니다.
 * 릴리스 태그는 "build-<versionCode>" 형식이고, APK 파일이 첨부되어 있어야 합니다.
 * 네트워크 작업이므로 메인 스레드가 아닌 곳에서 호출하세요.
 */
object Updater {

    data class Release(val versionCode: Int, val name: String, val notes: String, val apkUrl: String)

    val configured: Boolean get() = BuildConfig.UPDATE_REPO.isNotBlank()

    fun fetchLatest(): Release? {
        if (!configured) return null
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10_000; conn.readTimeout = 10_000
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val code = json.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return Release(code, json.optString("name"), json.optString("body"), a.optString("browser_download_url"))
                }
            }
            return null
        } finally { conn.disconnect() }
    }

    /** 현재 설치된 것보다 새 버전이면 반환 */
    fun newerRelease(): Release? = try {
        fetchLatest()?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    } catch (_: Exception) { null }

    fun download(context: Context, release: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, "callcard-${release.versionCode}.apk")
        var url = URL(release.apkUrl)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) { // GitHub 다운로드는 다른 호스트로 리다이렉트됩니다.
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 5) {
                url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue
            }
            if (code != 200) { conn.disconnect(); throw IllegalStateException("HTTP $code") }
            break
        }
        conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
        return file
    }

    /** '이 출처의 앱 설치' 허용 여부 (Android 8+) */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * PackageInstaller로 설치합니다.
     * Android 12 이상에서 이 앱이 스스로 설치한 적이 있으면(두 번째 업데이트부터) 확인 화면 없이 바로 설치됩니다.
     * 확인이 필요하면 InstallResultReceiver가 설치 화면을 띄우거나 알림을 보냅니다.
     */
    fun install(context: Context, apk: File, versionCode: Int) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                context, id,
                Intent(context, InstallResultReceiver::class.java).putExtra("versionCode", versionCode),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            )
            session.commit(result.intentSender)
        }
    }

    /** 새 버전이 있으면 내려받아 설치까지 진행합니다. (백그라운드 작업에서 호출) */
    fun autoUpdate(context: Context): Boolean {
        val r = newerRelease() ?: return false
        if (!canInstall(context)) {
            Notifier.showUpdate(context, "v1.${r.versionCode}", needsPermission = true)
            return false
        }
        val apk = download(context, r)
        install(context, apk, r.versionCode)
        return true
    }
}
