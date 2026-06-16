package com.example.glorytun

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AppUpdateManager(private val context: Context) {

    companion object {
        private const val LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/dnerdenchi/glorytun-android/releases/latest"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val REQUEST_TIMEOUT_MS = 15_000
        private const val PREFS_NAME = "AppUpdatePrefs"
        private const val KEY_LAST_AUTO_CHECK_AT = "last_auto_check_at"
        private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val response = getText(LATEST_RELEASE_API_URL)
            val latestRelease = parseRelease(response)
            if (latestRelease == null) {
                UpdateCheckResult.Error("GitHub Releases に APK が見つかりませんでした")
            } else if (isNewerVersion(latestRelease.versionName, BuildConfig.VERSION_NAME)) {
                UpdateCheckResult.UpdateAvailable(latestRelease)
            } else {
                UpdateCheckResult.NoUpdate
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "アップデート確認に失敗しました")
        }
    }

    suspend fun downloadApk(updateInfo: UpdateInfo): File = withContext(Dispatchers.IO) {
        val outputDir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val outputFile = File(outputDir, updateInfo.fileName)
        val connection = openConnection(updateInfo.apkUrl)
        try {
            connection.inputStream.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }
        outputFile
    }

    fun shouldRunAutomaticCheck(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheckAt = prefs.getLong(KEY_LAST_AUTO_CHECK_AT, 0L)
        return System.currentTimeMillis() - lastCheckAt >= AUTO_CHECK_INTERVAL_MS
    }

    fun markAutomaticCheckStarted() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_AUTO_CHECK_AT, System.currentTimeMillis())
            .apply()
    }

    fun showUpdateDialog(
        activity: AppCompatActivity,
        updateInfo: UpdateInfo,
        scope: CoroutineScope
    ) {
        val message = buildString {
            append("新しいバージョン ${updateInfo.versionName} が利用できます。")
            if (updateInfo.releaseNotes.isNotBlank()) {
                append("\n\n")
                append(updateInfo.releaseNotes.take(700))
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("アップデートがあります")
            .setMessage(message)
            .setPositiveButton("ダウンロードして更新") { _, _ ->
                if (!canRequestPackageInstalls(activity)) {
                    openInstallPermissionSettings(activity)
                    return@setPositiveButton
                }
                scope.launch {
                    installDownloadedUpdate(activity, updateInfo)
                }
            }
            .setNegativeButton("あとで", null)
            .setNeutralButton("リリースページ") { _, _ ->
                openReleasePage(activity, updateInfo.releasePageUrl)
            }
            .show()
    }

    private suspend fun installDownloadedUpdate(
        activity: AppCompatActivity,
        updateInfo: UpdateInfo
    ) {
        try {
            Toast.makeText(activity, "APK をダウンロードしています", Toast.LENGTH_SHORT).show()
            val apkFile = downloadApk(updateInfo)
            startInstall(activity, apkFile)
        } catch (e: Exception) {
            Toast.makeText(
                activity,
                e.message ?: "APK のダウンロードに失敗しました",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startInstall(activity: AppCompatActivity, apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(installIntent)
    }

    private fun canRequestPackageInstalls(activity: AppCompatActivity): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()
    }

    private fun openInstallPermissionSettings(activity: AppCompatActivity) {
        Toast.makeText(
            activity,
            "このアプリからのインストールを許可してから、もう一度更新してください",
            Toast.LENGTH_LONG
        ).show()
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivity(intent)
    }

    private fun openReleasePage(activity: AppCompatActivity, releasePageUrl: String) {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releasePageUrl)))
    }

    private fun getText(url: String): String {
        val connection = openConnection(url)
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = REQUEST_TIMEOUT_MS
        connection.readTimeout = REQUEST_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "BondVPN/${BuildConfig.VERSION_NAME}")
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("GitHub Releases への接続に失敗しました (${connection.responseCode})")
        }
        return connection
    }

    private fun parseRelease(jsonText: String): UpdateInfo? {
        val json = JSONObject(jsonText)
        val assets = json.optJSONArray("assets") ?: return null
        var apkName = ""
        var apkUrl = ""

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkName = name
                apkUrl = asset.optString("browser_download_url")
                break
            }
        }

        if (apkUrl.isBlank()) return null

        val tagName = json.optString("tag_name").trim()
        return UpdateInfo(
            versionName = tagName.removePrefix("v").ifBlank { tagName },
            releaseName = json.optString("name").ifBlank { tagName },
            releaseNotes = json.optString("body"),
            releasePageUrl = json.optString("html_url"),
            apkUrl = apkUrl,
            fileName = apkName.ifBlank { "BondVPN-$tagName.apk" }
        )
    }

    private fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
        val latestParts = latestVersion.extractVersionParts()
        val currentParts = currentVersion.extractVersionParts()
        val maxSize = maxOf(latestParts.size, currentParts.size)

        for (i in 0 until maxSize) {
            val latest = latestParts.getOrElse(i) { 0 }
            val current = currentParts.getOrElse(i) { 0 }
            if (latest != current) return latest > current
        }
        return false
    }

    private fun String.extractVersionParts(): List<Int> {
        return Regex("\\d+").findAll(this)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
    }
}

data class UpdateInfo(
    val versionName: String,
    val releaseName: String,
    val releaseNotes: String,
    val releasePageUrl: String,
    val apkUrl: String,
    val fileName: String
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val updateInfo: UpdateInfo) : UpdateCheckResult()
    data object NoUpdate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}
