package com.shilapi.xcertplay.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.DiPlayActivity
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLConnection

/**
 * Car tablet builds update from the private releases repo instead of upstream, whose APKs carry
 * another signing key. A read-only token is set once over adb and never leaves the app's storage.
 */
internal object CarTabletUpdates {
    private const val REPO = "drunkitguy/diplay-car-tablet-releases"
    private const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases?per_page=10"
    internal const val ASSET_PREFIX = "https://api.github.com/repos/$REPO/releases/assets/"
    private const val APK = "DiPlay-car-tablet.apk"
    private const val PREFS = "diplay_car_updates"
    private val BUILD = Regex("^v\\d+\\.\\d+\\.\\d+-car(\\d+)$")

    data class Setup(val build: Int?, val token: String?)

    /** Null for builds the car-tablet release workflow didn't make (it writes car-release.txt). */
    fun setup(context: Context): Setup? {
        val tag = runCatching { context.assets.open("car-release.txt").bufferedReader().use { it.readText().trim() } }
            .getOrNull() ?: return null
        return Setup(build(tag), prefs(context).getString("token", null))
    }

    fun latest(setup: Setup, userAgent: String, openConnection: (URL) -> URLConnection): UpdateRelease? {
        val token = setup.token ?: throw IOException("No update token yet. Set it once over adb.")
        val json = UpdateClient.fetchText(RELEASES_URL, "application/vnd.github+json", userAgent, openConnection,
            mapOf("Authorization" to "Bearer $token"))
        return pick(json, setup.build)
    }

    /** The highest carN release above the installed one that has the APK and its checksums. */
    internal fun pick(json: String, installed: Int?): UpdateRelease? {
        val releases = JSONArray(json)
        var best: UpdateRelease? = null
        var bestBuild = installed ?: -1
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val tag = release.optString("tag_name")
            val build = build(tag) ?: continue
            if (build <= bestBuild) continue
            val assets = release.optJSONArray("assets") ?: continue
            var apkUrl = ""
            var sumsUrl = ""
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val url = asset.optString("url").takeIf { it.startsWith(ASSET_PREFIX) } ?: continue
                when (asset.optString("name")) {
                    APK -> apkUrl = url
                    UpdateCatalog.CHECKSUMS_FILE -> sumsUrl = url
                }
            }
            if (apkUrl.isEmpty() || sumsUrl.isEmpty()) continue
            best = UpdateRelease(tag, APK, apkUrl, sumsUrl)
            bestBuild = build
        }
        return best
    }

    /** Release assets of the private repo download through the API with the token; anything else gets none. */
    fun headers(context: Context, url: String): Map<String, String> {
        if (!url.startsWith(ASSET_PREFIX)) return emptyMap()
        val token = prefs(context).getString("token", null) ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $token", "Accept" to "application/octet-stream")
    }

    /** Device owner installs its own update with no prompt. DiPlay restarts once it's replaced. */
    fun install(context: Context, apk: File) {
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(app, id, Intent(app, CarTabletUpdateReceiver::class.java), flags)
            session.commit(status.intentSender)
        }
    }

    fun saveToken(context: Context, token: String?) {
        prefs(context).edit().apply { if (token.isNullOrBlank()) remove("token") else putString("token", token.trim()) }.apply()
    }

    private fun build(tag: String) = BUILD.find(tag)?.groupValues?.get(1)?.toIntOrNull()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Install status from the silent update, and the restart after DiPlay replaced itself. */
class CarTabletUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (CarTabletUpdates.setup(context) == null) return
            runCatching {
                context.startActivity(Intent(context, DiPlayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        Log.i("DiPlay-Update", "silent install status=$status $message")
        UpdateAvailability.recordInstall(context, System.currentTimeMillis(), "silent status=$status $message")
    }
}

/** adb shell am broadcast -n <package>/com.shilapi.xcertplay.update.CarTabletUpdateToken --es token <token>. Only the shell holds DUMP. */
class CarTabletUpdateToken : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra("token")
        CarTabletUpdates.saveToken(context, token)
        resultData = if (token.isNullOrBlank()) "update token cleared" else "update token saved"
    }
}
