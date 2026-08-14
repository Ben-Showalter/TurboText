package com.turbotext.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class AppPickerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        ThemeHelper.apply(this)
        val list = findViewById<RecyclerView>(R.id.appList)
        list.layoutManager = LinearLayoutManager(this)

        Thread {
            val pm = packageManager
            val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val launcherApps = pm.queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo.applicationInfo }

            // Some apps — accessibility/utility apps especially — hide
            // their own launcher icon once configured, so relying on
            // launcher-visible apps alone can miss them entirely. Every
            // non-system installed app is included too, even without a
            // visible icon.
            val nonSystemApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }

            val apps = (launcherApps + nonSystemApps)
                .distinctBy { it.packageName }
                .filter { it.packageName != packageName } // no point shortcutting to ourselves
                .sortedBy { it.loadLabel(pm).toString().lowercase() }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                list.adapter = AppPickerAdapter(apps, pm) { app ->
                    AppShortcutHelper.setChosenApp(this, app.packageName, app.loadLabel(pm).toString())
                    finish()
                }
            }
        }.start()
    }
}
