package com.turbotext.app

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony

object DefaultSmsRoleHelper {

    fun isDefault(context: Context): Boolean =
        context.packageName == Telephony.Sms.getDefaultSmsPackage(context)

    /** Call from an Activity; result arrives in onActivityResult with this request code. */
    const val REQUEST_CODE = 5501

    fun requestDefault(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = activity.getSystemService(RoleManager::class.java)
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
            activity.startActivityForResult(intent, REQUEST_CODE)
        } else {
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, activity.packageName)
            activity.startActivityForResult(intent, REQUEST_CODE)
        }
    }
}
