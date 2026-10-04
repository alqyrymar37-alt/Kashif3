-- ============ كاشف: مخطط Supabase (خصوصية أولاً) ============
-- الأرقام لا تُخزَّن أبداً؛ فقط HMAC-SHA256 بمفتاح سري (pepper) على الخادم.
-- لا وصول مباشر للجداول: كل شيء عبر دوال RPC.

create extension if not exists pgcrypto with schema extensions;
create schema if not exists private;
revoke all on schema private from public, anon, authenticated;

create table if not exists private.secrets(key text primary key, value text not null);
-- نفّذ مرة واحدة فقط (واحفظ نسخة آمنة من المفتاح):
-- insert into private.secrets values ('pepper', encode(extensions.gen_random_bytes(32),'hex'));

-- ---------- الجداول ----------
create table if not exists public.reports(
  id bigserial primary key,
  num_hash bytea not null,
  reporter uuid not null,
  kind text not null check (kind in ('spam','safe')),
  category text check (category in ('telemarketing','scam','harassment','robocall','other')),
  created_at timestamptz not null default now(),
  unique (num_hash, reporter)
);
create table if not exists public.name_suggestions(
  id bigserial primary key,
  num_hash bytea not null,
  reporter uuid not null,
  name text not null check (char_length(btrim(name)) between 2 and 40),
  created_at timestamptz not null default now(),
  unique (num_hash, reporter)
);
-- أنشطة تجارية: التوثيق (verified) يتم من لوحة الإدارة/Edge Function بعد التحقق من الملكية
create table if not exists public.businesses(
  num_hash bytea primary key, name text not null, category text,
  owner uuid, verified boolean not null default false, verified_at timestamptz
);
-- جهات رسمية (بنوك، حكومة، طوارئ...) تُضاف يدوياً من مصادر موثوقة
create table if not exists public.official_numbers(
  num_hash bytea primary key, name text not null, category text, source_url text
);
-- أرقام طلب أصحابها إخفاء الأسماء عنها
create table if not exists public.suppressed(
  num_hash bytea primary key, requested_at timestamptz not null default now()
);
create index if not exists reports_h on public.reports(num_hash);
create index if not exists names_h on public.name_suggestions(num_hash);

alter table public.reports enable row level security;
alter table public.name_suggestions enable row level security;
alter table public.businesses enable row level security;
alter table public.official_numbers enable row level security;
alter table public.suppressed enable row level security;
revoke all on all tables in schema public from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;

-- ---------- دوال مساعدة داخلية ----------
create or replace function private.h(p text) returns bytea
language sql stable security definer set search_path = private, extensions as $$
  select hmac(p, (select value from private.secrets where key='pepper'), 'sha256');
$$;

create or replace function private.assert_ok(p text) returns void
language plpgsql security definer as $$
begin
  if auth.uid() is null then raise exception 'auth required'; end if;
  if p !~ '^\+[1-9][0-9]{7,14}$' then raise exception 'invalid number'; end if;
end$$;

create or replace function private.rate_limit() returns void
language plpgsql security definer set search_path = public as $$
begin
  if (select count(*) from reports where reporter=auth.uid() and created_at > now()-interval '1 day')
   + (select count(*) from name_suggestions where reporter=auth.uid() and created_at > now()-interval '1 day') >= 60
  then raise exception 'rate limit'; end if;
end$$;

-- ---------- الواجهة العامة (RPC) ----------
-- الاسم الجماعي لا يظهر إلا إذا اتفق عليه 5 مستخدمين مستقلين على الأقل
create or replace function public.lookup_number(p_e164 text) returns jsonb
language plpgsql security definer set search_path = public, private, extensions as $$
declare h bytea; v_name text; v_src text; v_spam int; v_safe int; v_cat text;
begin
  perform private.assert_ok(p_e164);
  h := private.h(p_e164);

  select name into v_name from official_numbers where num_hash = h;
  if v_name is not null then v_src := 'official'; end if;

  if v_name is null then
    select name into v_name from businesses where num_hash = h and verified;
    if v_name is not null then v_src := 'business'; end if;
  end if;

  if v_name is null and not exists (select 1 from suppressed where num_hash = h) then
    select (array_agg(name order by created_at desc))[1] into v_name
      from name_suggestions where num_hash = h
      group by lower(btrim(name)) having count(*) >= 5
      order by count(*) desc limit 1;
    if v_name is not null then v_src := 'community'; end if;
  end if;

  select count(*) filter (where kind='spam'), count(*) filter (where kind='safe')
    into v_spam, v_safe from reports where num_hash = h;
  select category into v_cat from reports
    where num_hash = h and kind='spam' and category is not null
    group by category order by count(*) desc limit 1;

  return jsonb_build_object('name',v_name,'source',v_src,'spam',v_spam,'safe',v_safe,'category',v_cat);
end$$;

create or replace function public.report_number(p_e164 text, p_kind text, p_category text default null)
returns void language plpgsql security definer set search_path = public, private, extensions as $$
begin
  perform private.assert_ok(p_e164);
  perform private.rate_limit();
  insert into reports(num_hash, reporter, kind, category)
  values (private.h(p_e164), auth.uid(), p_kind, p_category)
  on conflict (num_hash, reporter) do update
    set kind = excluded.kind, category = excluded.category, created_at = now();
end$$;

create or replace function public.suggest_name(p_e164 text, p_name text)
returns void language plpgsql security definer set search_path = public, private, extensions as $$
begin
  perform private.assert_ok(p_e164);
  perform private.rate_limit();
  insert into name_suggestions(num_hash, reporter, name)
  values (private.h(p_e164), auth.uid(), btrim(p_name))
  on conflict (num_hash, reporter) do update set name = excluded.name, created_at = now();
end$$;

-- طلب إخفاء الاسم (حق صاحب الرقم). للتحقق من الملكية لاحقاً: رمز SMS عبر Edge Function.
create or replace function public.request_removal(p_e164 text)
returns void language plpgsql security definer set search_path = public, private, extensions as $$
begin
  perform private.assert_ok(p_e164);
  insert into suppressed(num_hash) values (private.h(p_e164)) on conflict do nothing;
  delete from name_suggestions where num_hash = private.h(p_e164);
end$$;

-- الصلاحيات: المستخدمون (بما فيهم الدخول المجهول) ينفّذون الدوال فقط
revoke execute on function public.lookup_number(text), public.report_number(text,text,text),
  public.suggest_name(text,text), public.request_removal(text) from public, anon;
grant execute on function public.lookup_number(text), public.report_number(text,text,text),
  public.suggest_name(text,text), public.request_removal(text) to authenticated;
