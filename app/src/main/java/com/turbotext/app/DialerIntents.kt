package com.turbotext.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager

/**
 * Call, dial-pad and call-log intents aimed at the phone's default phone
 * app. A plain tel: intent showed Android's "Complete action using…"
 * chooser when a second phone app (a VoIP dialer, say) was installed,
 * which is awkward with only a keypad.
 */
object DialerIntents {

    /** [intent] limited to the default dialer, when that app handles it;
     *  otherwise unchanged (so something only another app handles still
     *  works). */
    fun toDefaultDialer(context: Context, intent: Intent): Intent {
        val pkg = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
            } else null
        } catch (e: Exception) {
            null
        }
        if (pkg.isNullOrEmpty()) {
            AppLog.event("Call", "no default phone app found; leaving the choice to Android")
            return intent
        }
        val aimed = Intent(intent).setPackage(pkg)
        val handled = try {
            context.packageManager.resolveActivity(aimed, PackageManager.MATCH_DEFAULT_ONLY) != null
        } catch (e: Exception) {
            false
        }
        AppLog.event("Call", "default phone app $pkg ${if (handled) "takes it" else "doesn't handle it; leaving the choice to Android"}")
        return if (handled) aimed else intent
    }

    /** Places a call (needs CALL_PHONE). */
    fun call(context: Context, number: String): Intent =
        toDefaultDialer(context, Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")))

    /** Opens the dial pad with [number] filled in (no permission needed). */
    fun dial(context: Context, number: String): Intent =
        toDefaultDialer(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))

    /** The phone's call log. */
    fun callLog(context: Context): Intent =
        toDefaultDialer(context, Intent(Intent.ACTION_VIEW).setType("vnd.android.cursor.dir/calls"))
}
