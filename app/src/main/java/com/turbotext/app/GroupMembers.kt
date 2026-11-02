package com.turbotext.app

import android.app.Activity
import androidx.appcompat.app.AlertDialog

/** "Show Members": who's in a group conversation — useful once it has a
 *  nickname and the names no longer show. Name and number per person. */
object GroupMembers {
    fun show(activity: Activity, threadId: Long, title: String) {
        val app = activity.applicationContext
        Thread {
            val members = SmsRepository(app).getThreadParticipants(threadId)
            val lines = members.map { number ->
                val name = ContactHelper.lookupName(app, number)
                if (name != null) "$name\n   ${ContactIndex.formatNumber(number)}" else ContactIndex.formatNumber(number)
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                AlertDialog.Builder(activity)
                    .setTitle("$title (${members.size})")
                    .setMessage(if (lines.isEmpty()) "Couldn't read this group's members." else lines.joinToString("\n"))
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }
}
