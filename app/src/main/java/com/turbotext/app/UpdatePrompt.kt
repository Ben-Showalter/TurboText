package com.turbotext.app

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.util.Locale

/**
 * Update dialogs shared by MainActivity's weekly automatic check and
 * Advanced settings' "Check for Updates" row — same flow as Flip Launcher's.
 */
object UpdatePrompt {

    /**
     * Runs [UpdateChecker.fetchLatest] off the main thread and hands the
     * result back on it: the newer release (or null when up to date), or the
     * failure. [onResult] is skipped if the activity has gone away meanwhile.
     */
    fun fetch(activity: Activity, onResult: (Result<UpdateRelease?>) -> Unit) {
        Thread {
            val result = try {
                Result.success(UpdateChecker.fetchLatest(activity))
            } catch (e: Exception) {
                android.util.Log.w("TurboTextUpdate", "update check failed", e)
                Result.failure(e)
            }
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) onResult(result)
            }
        }.start()
    }

    /**
     * Asks whether to install [release] (noting that Wi-Fi is preferred for
     * the download), and on Install downloads it and hands it to the system
     * installer.
     */
    fun show(activity: Activity, release: UpdateRelease) {
        val sizeMb = String.format(Locale.getDefault(), "%.1f", release.apkSizeBytes / 1_048_576.0)
        var message = "A new version of TurboText is ready to install ($sizeMb MB).\n\n" +
            "A Wi-Fi connection is preferred for downloading updates."
        if (!UpdateChecker.isOnWifi(activity)) {
            message += "\n\nYou are currently on mobile data."
        }
        AlertDialog.Builder(activity)
            .setTitle("Update available: v${release.versionName}")
            .setMessage(message)
            .setPositiveButton("Install") { _, _ -> downloadAndInstall(activity, release) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstall(activity: Activity, release: UpdateRelease) {
        val progress = AlertDialog.Builder(activity)
            .setMessage("Downloading update…")
            .setCancelable(false)
            .show()
        Thread {
            val apk = try {
                UpdateChecker.download(activity, release)
            } catch (e: Exception) {
                android.util.Log.w("TurboTextUpdate", "update download failed", e)
                null
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                progress.dismiss()
                if (apk == null) {
                    Toast.makeText(activity, "Update download failed", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                try {
                    activity.startActivity(UpdateChecker.installIntent(activity, apk))
                } catch (e: Exception) {
                    Toast.makeText(activity, "Update download failed", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
