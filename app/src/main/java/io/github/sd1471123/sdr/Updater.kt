package io.github.sd1471123.sdr

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A published build that is newer than the one on the phone. */
class UpdateInfo(
    val version: String,
    /** Direct APK download URL; null when the release has no attachment. */
    val apkUrl: String?,
    val pageUrl: String?,
)

/**
 * Self-update through the GitHub API. Every release carries the signed APK, so the highest
 * release tag is the version of record; the tag list is only a fallback for comparison when
 * no release has been published yet.
 *
 * All methods block — call them from a worker thread.
 */
class Updater(private val activity: Activity) {

    fun currentVersion(): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    /** Highest non-draft, non-prerelease version with an APK, or null when nothing is published. */
    fun fetchLatest(): UpdateInfo? {
        val releases = getJson("$API/releases") as? JSONArray ?: return null
        var best: UpdateInfo? = null
        var bestVer: List<Int>? = null
        for (i in 0 until releases.length()) {
            val r = releases.optJSONObject(i) ?: continue
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val ver = parseVersion(r.optString("tag_name")) ?: continue
            if (bestVer != null && !isNewer(ver, bestVer)) continue

            var apk: String? = null
            val assets = r.optJSONArray("assets")
            if (assets != null) {
                for (j in 0 until assets.length()) {
                    val a = assets.optJSONObject(j) ?: continue
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apk = a.optString("browser_download_url")
                        break
                    }
                }
            }
            bestVer = ver
            best = UpdateInfo(ver.joinToString("."), apk, r.optString("html_url"))
        }
        if (best != null) return best

        // Nothing released yet: compare against the tag list (no install possible).
        val tags = getJson("$API/tags") as? JSONArray ?: return null
        var tagVer: List<Int>? = null
        var tagName: String? = null
        for (i in 0 until tags.length()) {
            val t = tags.optJSONObject(i) ?: continue
            val ver = parseVersion(t.optString("name")) ?: continue
            if (tagVer == null || isNewer(ver, tagVer)) {
                tagVer = ver
                tagName = ver.joinToString(".")
            }
        }
        return tagName?.let { UpdateInfo(it, null, null) }
    }

    /** Streams [UpdateInfo.apkUrl] into the cache directory, reporting 0..100. */
    fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val url = info.apkUrl ?: throw IllegalStateException("this release has no APK")
        val dest = File(activity.cacheDir, "update-${info.version}.apk")
        val conn = open(url, "application/octet-stream", 20_000)
        try {
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (dest.length() < 1024) throw IllegalStateException("downloaded file is empty")
        return dest
    }

    /** "Install unknown apps" is a per-app permission from Android 8 on. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
        }
    }

    /**
     * Opens the system installer on [apk]. A `file://` URI is refused from Android 7 on, and a
     * [PackageInstaller] session leaves the confirmation screen to a result broadcast, which
     * Android 12 no longer lets a background receiver launch — so the file is exposed through
     * [ApkProvider] and the installer is started from the visible activity instead.
     */
    fun install(apk: File) {
        val uri = Uri.parse("content://${activity.packageName}.apk/${apk.name}")
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(intent)
    }

    // ---- helpers ----------------------------------------------------------

    private fun open(url: String, accept: String, readTimeout: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = readTimeout
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "C5-SDR-Android")
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("HTTP ${conn.responseCode}")
        }
        return conn
    }

    private fun getJson(url: String): Any? {
        val conn = open(url, "application/vnd.github+json", 10_000)
        return try {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            JSONTokener(text).nextValue()
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val API = "https://api.github.com/repos/sd1471123/ESP32_C5_Wifi_SDR_android"

        /** "v0.1.0" / "0.1.0-beta" -> [0, 1, 0]; null when the tag is not version-like. */
        fun parseVersion(tag: String): List<Int>? {
            val core = tag.trim().trimStart('v', 'V').split('-', '+')[0]
            if (core.isEmpty()) return null
            val parts = core.split('.')
            val nums = ArrayList<Int>(parts.size)
            for (p in parts) nums.add(p.toIntOrNull() ?: return null)
            return nums
        }

        /** True when [a] is strictly newer than [b], comparing numerically per component. */
        fun isNewer(a: List<Int>, b: List<Int>): Boolean {
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        fun isNewer(remoteTag: String, localVersion: String): Boolean {
            val r = parseVersion(remoteTag) ?: return false
            val l = parseVersion(localVersion) ?: return false
            return isNewer(r, l)
        }
    }
}
