package com.example.mycar.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.pm.PackageInfoCompat
import com.example.mycar.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class AvailableUpdate(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val pageUrl: String? = null,
)

enum class UpdateCheckOutcome {
    UPDATE_AVAILABLE,
    UP_TO_DATE,
    FAILED,
}

/**
 * Checks the app's distribution channel for a newer build and downloads it.
 *
 * Deliberately dependency-free (HttpURLConnection + org.json) so the app carries no HTTP or
 * serialization stack just for updates. All callbacks are delivered on the main thread.
 *
 * The channel is chosen at build time: stable builds point at `mycar.sting.hr`, beta builds at
 * `mycar-beta.sting.hr` (see `UPDATE_BASE_URL` in `app/build.gradle.kts`).
 */
class UpdateManager(private val context: Context) {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "update-check").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Base URL of the distribution server. Editable so a build can be pointed at localhost. */
    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty().trimEnd('/')
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim()).apply()

    fun check(force: Boolean = false, onResult: (UpdateCheckOutcome, AvailableUpdate?) -> Unit) {
        worker.execute {
            val outcome = runCatching { doCheck(force) }
                .getOrElse { UpdateCheckOutcome.FAILED to null }
            main.post { onResult(outcome.first, outcome.second) }
        }
    }

    fun download(
        update: AvailableUpdate,
        onProgress: (Int) -> Unit,
        onResult: (Result<File>) -> Unit,
    ) {
        worker.execute {
            val result = runCatching { doDownload(update, onProgress) }
            main.post { onResult(result) }
        }
    }

    fun dismiss(update: AvailableUpdate) {
        prefs.edit().putInt(KEY_DISMISSED_VERSION, update.versionCode).apply()
    }

    private fun doCheck(force: Boolean): Pair<UpdateCheckOutcome, AvailableUpdate?> {
        val installedCode = installedVersionCode() ?: return UpdateCheckOutcome.FAILED to null
        val body = httpGet("$baseUrl/version") ?: return UpdateCheckOutcome.FAILED to null
        val json = JSONObject(body)
        val versionCode = json.optInt("versionCode", 0)
        val versionName = json.optString("versionName")
        if (versionCode <= installedCode) return UpdateCheckOutcome.UP_TO_DATE to null
        if (!force && dismissedVersion() >= versionCode) return UpdateCheckOutcome.UP_TO_DATE to null

        val pageUrl = json.optString("pageUrl").ifBlank { null } ?: baseUrl
        val update = AvailableUpdate(
            versionName = versionName,
            versionCode = versionCode,
            downloadUrl = json.optString("apkUrl").ifBlank { pageUrl },
            pageUrl = pageUrl,
        )
        return UpdateCheckOutcome.UPDATE_AVAILABLE to update
    }

    private fun doDownload(update: AvailableUpdate, onProgress: (Int) -> Unit): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        // Clear previous downloads so a stale APK can never be installed by mistake.
        directory.listFiles()?.forEach { it.delete() }
        val target = File(directory, "mycar-${update.versionCode}.apk")

        val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.android.package-archive")
            instanceFollowRedirects = true
        }
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $status")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var received = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        if (total > 0) {
                            onProgress(((received * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        verify(target, update)
        return target
    }

    /**
     * Rejects anything that is not this app's APK at the expected version. This is the check
     * that catches a Cloudflare Access login page coming back instead of an APK.
     */
    private fun verify(file: File, update: AvailableUpdate) {
        val info = archiveInfo(file) ?: throw IOException("Downloaded file is not an APK")
        if (info.packageName != context.packageName) {
            throw IOException("Unexpected package ${info.packageName}")
        }
        if (PackageInfoCompat.getLongVersionCode(info) != update.versionCode.toLong()) {
            throw IOException("Downloaded version does not match")
        }
    }

    private fun archiveInfo(file: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }

    private fun httpGet(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun installedVersionCode(): Long? = runCatching {
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0),
        )
    }.getOrNull()

    private fun dismissedVersion(): Int = prefs.getInt(KEY_DISMISSED_VERSION, 0)

    private companion object {
        const val PREFS_NAME = "updates"
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_DISMISSED_VERSION = "dismissedVersion"

        /** Stable or beta, baked in per build type. */
        val DEFAULT_BASE_URL = BuildConfig.UPDATE_BASE_URL
    }
}
