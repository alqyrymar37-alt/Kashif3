package com.example.kashif

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// يجب أن تطابق قيد category في directory.sql حرفياً
val CATS = listOf("صيدلية", "طبيب / مستشفى", "مطعم", "صرافة / حوالات", "توصيل", "سائق / نقل", "كهرباء / سباكة", "متجر", "خدمات أخرى")
val CITIES = listOf("صنعاء", "عدن", "تعز", "الحديدة", "إب", "المكلا", "ذمار", "مأرب", "سيئون", "أخرى")

@Composable
private fun Chips(opts: List<String>, sel: String?, onSel: (String?) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        opts.forEach { o -> FilterChip(sel == o, { onSel(if (sel == o) null else o) }, { Text(o) }) }
    }
}

@Composable
fun ColumnScope.Directory(api: Api) {
    val ctx: Context = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<String?>(null) }
    var city by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<Biz>?>(null) }
    var msg by remember { mutableStateOf("") }
    var bn by remember { mutableStateOf("") }
    var bp by remember { mutableStateOf("") }
    var bc by remember { mutableStateOf<String?>(CATS[0]) }
    var bcity by remember { mutableStateOf<String?>(CITIES[0]) }
    var pendingId by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }

    fun run(b: suspend () -> Unit) = scope.launch {
        try { b() } catch (e: Exception) { msg = "تعذّر تنفيذ العملية، حاول لاحقاً" }
    }

    Text("🏪 دليل الأنشطة الموثّقة", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(q, { q = it }, label = { Text("ابحث بالاسم") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Chips(CATS, cat) { cat = it }
    Chips(CITIES, city) { city = it }
    Button(onClick = { run { list = api.directorySearch(q, cat, city); msg = "" } }) { Text("🔍 بحث") }

    list?.let { l ->
        if (l.isEmpty()) Text("لا توجد نتائج بعد. كن أول من يسجّل نشاطه!")
        l.forEach { b ->
            Card { Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("✅ ${b.name}", style = MaterialTheme.typography.titleMedium)
                    Text("${b.category} • ${b.city}")
                }
                Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${b.phone}"))) }) { Text("📞") }
            } }
        }
    }

    HorizontalDivider()
    Text("➕ سجّل نشاطك", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(bn, { bn = it }, label = { Text("اسم النشاط") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(bp, { bp = it }, label = { Text("رقم النشاط (سنرسل إليه رمز تحقق)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Chips(CATS, bc) { bc = it ?: bc }
    Chips(CITIES, bcity) { bcity = it ?: bcity }
    Button(onClick = {
        val e = Numbers.toE164(ctx, bp)
        if (bn.isBlank() || e == null) { msg = "أدخل اسماً ورقماً صحيحاً"; return@Button }
        run {
            val id = api.businessRegister(bn, bc!!, bcity!!, e)
            pendingId = id
            api.sendCode(id)
            msg = "أرسلنا رمز التحقق إلى $e"
        }
    }) { Text("تسجيل وإرسال رمز التحقق") }

    pendingId?.let { id ->
        OutlinedTextField(code, { code = it }, label = { Text("رمز التحقق (6 أرقام)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = { run {
            if (api.businessVerify(id, code)) { msg = "تم توثيق نشاطك ✅"; pendingId = null; code = "" }
            else msg = "الرمز غير صحيح أو منتهي"
        } }) { Text("تأكيد") }
        TextButton(onClick = { run { api.sendCode(id); msg = "أُعيد إرسال الرمز" } }) { Text("إعادة إرسال الرمز") }
    }
    if (msg.isNotEmpty()) Text(msg)
}
