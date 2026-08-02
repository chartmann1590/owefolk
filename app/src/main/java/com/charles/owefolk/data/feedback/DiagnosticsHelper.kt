package com.charles.owefolk.data.feedback

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.Environment
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat

/**
 * Collects generic, non-sensitive device/app diagnostics formatted as markdown
 * for inclusion in an optional section of a feedback report.
 *
 * Explicitly collects ONLY non-identifying technical info: app name/package,
 * version, device brand/model, Android version/API, locale, timezone, and
 * coarse storage/memory figures. It deliberately does NOT collect contacts,
 * SMS, location, photos, accounts, private files, or anything from
 * local.properties or BuildConfig fields that could carry secrets. None of the
 * BuildConfig fields here hold the token (the token is server-side only).
 */
object DiagnosticsHelper {

    fun collect(context: Context): String {
        val pm = context.packageManager
        val pkgInfo = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        val versionName = pkgInfo?.versionName ?: "unknown"
        val versionCode = pkgInfo?.longVersionCode?.toString() ?: "unknown"

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }

        val storage = runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val free = stat.availableBytes
            val total = stat.totalBytes
            formatBytes(free) to formatBytes(total)
        }.getOrNull() ?: ("?" to "?")

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
            .format(Date(System.currentTimeMillis()))

        return buildString {
            append("## Diagnostics\n\n")
            append("- App: ${appName(context)}\n")
            append("- Package: ${context.packageName}\n")
            append("- Version: $versionName ($versionCode)\n")
            append("- Device: ${Build.MODEL}\n")
            append("- Manufacturer: ${Build.MANUFACTURER}\n")
            append("- Brand: ${Build.BRAND}\n")
            append("- Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n")
            append("- Locale: ${Locale.getDefault().toString()}\n")
            append("- Time Zone: ${TimeZone.getDefault().id}\n")
            append("- Storage Free/Total: ${storage.first} / ${storage.second}\n")
            append("- Memory Free/Total: ${formatBytes(memInfo.availMem)} / ${formatBytes(memInfo.totalMem)}\n")
            append("- Reported at: $timestamp\n")
        }
    }

    private fun appName(context: Context): String {
        val pm = context.packageManager
        return runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(context.packageName, 0)).toString()
        }.getOrDefault(context.packageName)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }
}
