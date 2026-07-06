package com.datacall.ccp.call

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.datacall.ccp.util.PhoneUtils

object OutgoingCallLauncher {
    fun dial(context: Context, phone: String, hasCallPermission: Boolean): Boolean {
        val normalized = PhoneUtils.normalizePhone(phone)
        val uri = Uri.parse("tel:$normalized")
        return if (hasCallPermission) {
            val intent = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } else {
            val intent = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Toast.makeText(context, "Grant CALL_PHONE for auto-dial", Toast.LENGTH_LONG).show()
            false
        }
    }
}
