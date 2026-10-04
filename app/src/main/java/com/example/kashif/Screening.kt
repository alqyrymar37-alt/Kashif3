package com.example.kashif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class ScreeningService : CallScreeningService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onScreenCall(d: Call.Details) {
        val allow = CallScreeningService.CallResponse.Builder().build()
        if (d.callDirection != Call.Details.DIRECTION_INCOMING) { respondToCall(d, allow); return }

        val e164 = Numbers.toE164(this, d.handle?.schemeSpecificPart)
        if (e164 == null) { respondToCall(d, allow); return }   // رقم مخفي/غير صالح

        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        if (prefs.getStringSet("blocked", emptySet())!!.contains(e164)) {
            respondToCall(d, CallScreeningService.CallResponse.Builder().setDisallowCall(true).setRejectCall(true).setSkipNotification(true).build())
            return
        }

        scope.launch {
            // يجب الرد سريعاً: مهلة 2.5 ثانية ثم نسمح بالمكالمة في كل الأحوال
            val info = try { withTimeout(2500) { Api(this@ScreeningService).lookup(e164) } } catch (_: Exception) { null }
            val autoBlock = prefs.getBoolean("auto_block", false)
            val isSpam = info != null && info.spam >= 5 && info.spam > info.safe * 2
            if (autoBlock && isSpam) {
                respondToCall(d, CallScreeningService.CallResponse.Builder().setDisallowCall(true).setRejectCall(true).build())
            } else {
                respondToCall(d, allow)
            }
            if (info != null) show(e164, info)
        }
    }

    private fun show(num: String, i: Info) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("caller", "معلومات المتصل", NotificationManager.IMPORTANCE_HIGH))
        val warn = i.spam >= 3 && i.spam > i.safe
        val title = i.name ?: if (warn) "⚠️ رقم مشبوه" else "رقم غير معروف"
        val text = buildString {
            append(num)
            if (i.spam > 0) append(" • بلاغات: ${i.spam}")
            i.category?.let { append(" • $it") }
        }
        val n = NotificationCompat.Builder(this, "caller")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(title).setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
        try { nm.notify(1001, n) } catch (_: SecurityException) {}
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
