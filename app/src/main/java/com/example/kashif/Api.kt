package com.example.kashif

import android.content.Context
import android.telephony.TelephonyManager
import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Biz(val id: String, val name: String, val category: String, val city: String, val phone: String)
data class Info(val name: String?, val source: String?, val spam: Int, val safe: Int, val category: String?)

object Numbers {
    /** يحوّل أي رقم إلى صيغة E.164 اعتماداً على دولة الشريحة. */
    fun toE164(ctx: Context, raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val region = tm.simCountryIso?.uppercase()?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country.ifBlank { "EG" }
        val u = PhoneNumberUtil.getInstance()
        return try {
            val n = u.parse(raw, region)
            if (u.isValidNumber(n)) u.format(n, PhoneNumberUtil.PhoneNumberFormat.E164) else null
        } catch (e: Exception) { null }
    }
}

class Api(ctx: Context, timeoutSec: Long = 3) {
    private val sp = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().callTimeout(timeoutSec, TimeUnit.SECONDS).build()
    private val base = BuildConfig.SUPABASE_URL
    private val key = BuildConfig.SUPABASE_ANON_KEY
    private val json = "application/json".toMediaType()

    private fun post(url: String, body: String, bearer: String?): Pair<Int, String> {
        val b = Request.Builder().url(url).header("apikey", key).post(body.toRequestBody(json))
        if (bearer != null) b.header("Authorization", "Bearer $bearer")
        http.newCall(b.build()).execute().use { return it.code to (it.body?.string() ?: "") }
    }

    private fun saveSession(t: String) {
        val o = JSONObject(t)
        sp.edit().putString("at", o.getString("access_token"))
            .putString("rt", o.getString("refresh_token")).apply()
    }

    /** دخول مجهول (Anonymous Sign-in): لا بريد ولا رقم هاتف. */
    private fun login() {
        sp.getString("rt", null)?.let { rt ->
            val (c, t) = post("$base/auth/v1/token?grant_type=refresh_token", """{"refresh_token":"$rt"}""", null)
            if (c == 200) { saveSession(t); return }
        }
        val (c, t) = post("$base/auth/v1/signup", "{}", null)
        if (c == 200) saveSession(t) else error("auth $c")
    }

    private fun call(url: String, body: String): String {
        if (sp.getString("at", null) == null) login()
        var (c, t) = post(url, body, sp.getString("at", null))
        if (c == 401) { login(); val r = post(url, body, sp.getString("at", null)); c = r.first; t = r.second }
        if (c !in 200..299) error("call $c")
        return t
    }
    private fun rpc(fn: String, args: JSONObject) = call("$base/rest/v1/rpc/$fn", args.toString())

    suspend fun lookup(e164: String): Info = withContext(Dispatchers.IO) {
        val o = JSONObject(rpc("lookup_number", JSONObject().put("p_e164", e164)))
        Info(o.optString("name").takeIf { !o.isNull("name") && it.isNotBlank() },
            o.optString("source").takeIf { !o.isNull("source") },
            o.optInt("spam"), o.optInt("safe"),
            o.optString("category").takeIf { !o.isNull("category") })
    }

    suspend fun report(e164: String, kind: String, category: String? = null) = withContext(Dispatchers.IO) {
        rpc("report_number", JSONObject().put("p_e164", e164).put("p_kind", kind).put("p_category", category ?: JSONObject.NULL)); Unit
    }

    suspend fun suggestName(e164: String, name: String) = withContext(Dispatchers.IO) {
        rpc("suggest_name", JSONObject().put("p_e164", e164).put("p_name", name)); Unit
    }

    suspend fun requestRemoval(e164: String) = withContext(Dispatchers.IO) {
        rpc("request_removal", JSONObject().put("p_e164", e164)); Unit
    }

    // ---------- الدليل ----------
    suspend fun directorySearch(q: String, cat: String?, city: String?): List<Biz> = withContext(Dispatchers.IO) {
        val arr = JSONArray(rpc("directory_search", JSONObject()
            .put("p_q", if (q.isBlank()) JSONObject.NULL else q)
            .put("p_cat", cat ?: JSONObject.NULL).put("p_city", city ?: JSONObject.NULL)))
        List(arr.length()) { val o = arr.getJSONObject(it)
            Biz(o.getString("id"), o.getString("name"), o.getString("category"), o.getString("city"), o.getString("phone")) }
    }

    suspend fun businessRegister(name: String, cat: String, city: String, phone: String): String = withContext(Dispatchers.IO) {
        rpc("business_register", JSONObject().put("p_name", name).put("p_cat", cat).put("p_city", city).put("p_phone", phone)).trim('"', ' ', '\n')
    }

    /** يطلب من Edge Function إرسال رمز SMS إلى رقم النشاط. */
    suspend fun sendCode(id: String) = withContext(Dispatchers.IO) {
        call("$base/functions/v1/send-business-code", JSONObject().put("id", id).toString()); Unit
    }

    suspend fun businessVerify(id: String, code: String): Boolean = withContext(Dispatchers.IO) {
        rpc("business_verify", JSONObject().put("p_id", id).put("p_code", code)).trim() == "true"
    }
}
