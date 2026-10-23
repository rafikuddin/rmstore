package com.example.attendance

import org.json.JSONObject

/** Small helpers for the SMS feature (the server does the real work and all the checks). */
object SmsUi {
    /** One line about what happened to the "also send as SMS" part of a notice. */
    fun summary(sms: JSONObject?): String {
        if (sms == null) return ""
        return if (sms.optBoolean("ok")) {
            var t = "📱 SMS sent to ${sms.optInt("sent")}"
            if (sms.optInt("failed") > 0) t += " · ${sms.optInt("failed")} failed"
            if (sms.optInt("noPhone") > 0) t += " · ${sms.optInt("noPhone")} have no phone number"
            t
        } else "📱 SMS not sent: " + sms.optString("error", "unknown problem")
    }

    /** The question asked before texting, from a smsPreview reply. */
    fun confirmText(preview: JSONObject, who: String): String {
        val n = preview.optInt("people")
        var t = "Also send this as an SMS to $who?\n\n$n ${if (n == 1) "person" else "people"} will get a text"
        if (preview.optInt("noPhone") > 0) t += " (${preview.optInt("noPhone")} have no phone number saved)"
        t += ".\nThat uses $n of the ${preview.optInt("left")} text messages left this month."
        return t
    }
}
