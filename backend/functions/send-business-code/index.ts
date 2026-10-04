// Supabase Edge Function: يرسل رمز تحقق SMS لصاحب النشاط
// النشر:  supabase functions deploy send-business-code
// الأسرار: supabase secrets set TWILIO_SID=... TWILIO_TOKEN=... TWILIO_FROM=+1...
// ملاحظة: تحقق من دعم مزوّدك للإرسال إلى اليمن وتكلفته، أو استخدم admin_verify_business للتوثيق اليدوي.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, apikey, content-type" };
const j = (b: unknown, s = 200) => new Response(JSON.stringify(b), { status: s, headers: { ...cors, "Content-Type": "application/json" } });

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  try {
    const url = Deno.env.get("SUPABASE_URL")!;
    const userClient = createClient(url, Deno.env.get("SUPABASE_ANON_KEY")!,
      { global: { headers: { Authorization: req.headers.get("Authorization") ?? "" } } });
    const { data: { user } } = await userClient.auth.getUser();
    if (!user) return j({ error: "unauthorized" }, 401);

    const { id } = await req.json();
    const admin = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
    const { data: row } = await admin.from("directory").select("id")
      .eq("id", id).eq("owner", user.id).eq("status", "pending").maybeSingle();
    if (!row) return j({ error: "not found" }, 404);

    const code = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1000000).padStart(6, "0");
    const { data: phone, error } = await admin.rpc("admin_store_code", { p_id: id, p_code: code });
    if (error) return j({ error: error.message }, 429);

    const sid = Deno.env.get("TWILIO_SID")!, tok = Deno.env.get("TWILIO_TOKEN")!;
    const res = await fetch(`https://api.twilio.com/2010-04-01/Accounts/${sid}/Messages.json`, {
      method: "POST",
      headers: { Authorization: "Basic " + btoa(`${sid}:${tok}`), "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ To: phone as string, From: Deno.env.get("TWILIO_FROM")!,
        Body: `رمز التحقق في كاشف الأرقام الجديد: ${code}` }),
    });
    return res.ok ? j({ ok: true }) : j({ error: "sms failed" }, 502);
  } catch (_) { return j({ error: "bad request" }, 400); }
});
