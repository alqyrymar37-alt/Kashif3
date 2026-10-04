package com.example.kashif

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContent { MaterialTheme { Surface { App() } } }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("app", Context.MODE_PRIVATE) }
    val api = remember { Api(ctx, 15) }
    val scope = rememberCoroutineScope()
    var consent by remember { mutableStateOf(prefs.getBoolean("consent", false)) }
    var tab by remember { mutableStateOf(0) }
    var input by remember { mutableStateOf("") }
    var nameIn by remember { mutableStateOf("") }
    var e164 by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<Info?>(null) }
    var msg by remember { mutableStateOf("") }
    var autoBlock by remember { mutableStateOf(prefs.getBoolean("auto_block", false)) }
    val roleL = rememberLauncherForActivityResult(StartActivityForResult()) {}
    val notifL = rememberLauncherForActivityResult(RequestPermission()) {}

    fun run(block: suspend () -> Unit) = scope.launch {
        try { block() } catch (e: Exception) { msg = "تعذّر الاتصال بالخادم" }
    }

    Column(Modifier.padding(16.dp).statusBarsPadding().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("🛡️ كاشف الأرقام الجديد", style = MaterialTheme.typography.headlineMedium)

        if (!consent) {
            Text("الخصوصية: لا نرفع جهات اتصالك. عند البحث يُرسل الرقم فقط إلى خادمنا ويُحوَّل فوراً إلى بصمة مشفّرة لا يمكن عكسها، ولا نخزّن الرقم نفسه. " +
                "الأسماء لا تُعرض إلا بعد اتفاق 5 مستخدمين مستقلين، ويمكنك طلب إخفاء رقمك في أي وقت.")
            Button(onClick = { prefs.edit().putBoolean("consent", true).apply(); consent = true }) { Text("أوافق وأتابع") }
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == 0, { tab = 0 }, { Text("الكشف") })
            FilterChip(tab == 1, { tab = 1 }, { Text("الدليل") })
        }
        if (tab == 1) { Directory(api); return@Column }

        Button(onClick = {
            val rm = ctx.getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING))
                roleL.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
            if (Build.VERSION.SDK_INT >= 33) notifL.launch(Manifest.permission.POST_NOTIFICATIONS)
        }) { Text("تفعيل كشف المكالمات الواردة") }

        Row { Text("حظر تلقائي للأرقام الخطرة", Modifier.weight(1f))
            Switch(autoBlock, { autoBlock = it; prefs.edit().putBoolean("auto_block", it).apply() }) }

        OutlinedTextField(input, { input = it }, label = { Text("رقم للبحث") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = {
            val n = Numbers.toE164(ctx, input)
            if (n == null) { msg = "رقم غير صالح"; return@Button }
            e164 = n; msg = ""
            run { info = api.lookup(n) }
        }) { Text("🔍 بحث") }

        info?.let { i ->
            val src = when (i.source) { "official" -> "جهة رسمية"; "business" -> "نشاط موثّق"; "community" -> "اسم المجتمع"; else -> "غير معروف" }
            Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(e164 ?: "", style = MaterialTheme.typography.titleMedium)
                Text("الاسم: ${i.name ?: "—"} ($src)")
                Text("بلاغات مزعج: ${i.spam} • آمن: ${i.safe}" + (i.category?.let { " • $it" } ?: ""))
            } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run { api.report(e164!!, "spam", "scam"); info = api.lookup(e164!!) } }) { Text("🚫 مزعج") }
                OutlinedButton(onClick = { run { api.report(e164!!, "safe"); info = api.lookup(e164!!) } }) { Text("👍 آمن") }
                OutlinedButton(onClick = {
                    val s = prefs.getStringSet("blocked", emptySet())!!.toMutableSet().apply { add(e164!!) }
                    prefs.edit().putStringSet("blocked", s).apply(); msg = "تم الحظر"
                }) { Text("⛔ حظر") }
            }
            OutlinedTextField(nameIn, { nameIn = it }, label = { Text("اقترح اسماً لهذا الرقم") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { run { api.suggestName(e164!!, nameIn); msg = "شكراً! يُعرض الاسم بعد تأكيد 5 مستخدمين." } }) { Text("إرسال الاسم") }
            TextButton(onClick = { run { api.requestRemoval(e164!!); msg = "تم إخفاء الاسم لهذا الرقم" } }) { Text("هذا رقمي: اطلب إخفاءه") }
        }
        if (msg.isNotEmpty()) Text(msg)
    }
}
