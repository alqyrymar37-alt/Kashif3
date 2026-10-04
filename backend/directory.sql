-- ===== دليل الأنشطة الموثّقة (شغّله بعد schema.sql) =====
-- الأنشطة التجارية تنشر رقمها عن قصد، لذا يُخزَّن رقمها العلني؛ أما بصمة الرقم فتُستخدم لربطه بنتائج الكشف.
create table if not exists public.directory(
  id uuid primary key default gen_random_uuid(),
  owner uuid not null,
  name text not null check (char_length(btrim(name)) between 2 and 60),
  category text not null check (category in ('صيدلية','طبيب / مستشفى','مطعم','صرافة / حوالات','توصيل','سائق / نقل','كهرباء / سباكة','متجر','خدمات أخرى')),
  city text not null check (char_length(city) between 2 and 30),
  phone text not null check (phone ~ '^\+[1-9][0-9]{7,14}$'),
  num_hash bytea not null,
  status text not null default 'pending' check (status in ('pending','verified','rejected')),
  created_at timestamptz not null default now(),
  verified_at timestamptz
);
-- رقم واحد موثّق فقط (التسجيلات المعلّقة المتكررة لا تحجب المالك الحقيقي)
create unique index if not exists directory_verified_phone on public.directory(phone) where status = 'verified';
create index if not exists directory_lookup on public.directory(status, category, city);
alter table public.directory enable row level security;
revoke all on public.directory from anon, authenticated;

create table if not exists private.biz_codes(
  biz_id uuid primary key references public.directory(id) on delete cascade,
  code_hash bytea not null,
  expires_at timestamptz not null,
  attempts int not null default 0,
  sent_at timestamptz not null default now()
);

-- ترقية نشاط إلى «موثّق» + إظهاره في نتائج كشف المكالمات
create or replace function private.promote(p_id uuid) returns void
language plpgsql security definer set search_path = public, private, extensions as $$
declare d directory%rowtype;
begin
  select * into d from directory where id = p_id;
  if not found then raise exception 'not found'; end if;
  if exists (select 1 from directory where phone = d.phone and status = 'verified' and id <> p_id)
    then raise exception 'phone already verified'; end if;
  update directory set status = 'verified', verified_at = now() where id = p_id;
  insert into businesses(num_hash, name, category, owner, verified, verified_at)
  values (d.num_hash, d.name, d.category, d.owner, true, now())
  on conflict (num_hash) do update
    set name = excluded.name, category = excluded.category, owner = excluded.owner, verified = true, verified_at = now();
  delete from private.biz_codes where biz_id = p_id;
end$$;

-- ---------- واجهة المستخدم ----------
create or replace function public.business_register(p_name text, p_cat text, p_city text, p_phone text)
returns uuid language plpgsql security definer set search_path = public, private, extensions as $$
declare v_id uuid;
begin
  perform private.assert_ok(p_phone);
  if (select count(*) from directory where owner = auth.uid() and status = 'pending') >= 5
    then raise exception 'too many pending'; end if;
  insert into directory(owner, name, category, city, phone, num_hash)
  values (auth.uid(), btrim(p_name), p_cat, btrim(p_city), p_phone, private.h(p_phone))
  returning id into v_id;
  return v_id;
end$$;

-- يتحقق من الرمز المرسل بـ SMS (5 محاولات كحد أقصى، صلاحية 10 دقائق)
create or replace function public.business_verify(p_id uuid, p_code text)
returns boolean language plpgsql security definer set search_path = public, private, extensions as $$
declare c private.biz_codes%rowtype;
begin
  if auth.uid() is null then raise exception 'auth required'; end if;
  if not exists (select 1 from directory where id = p_id and owner = auth.uid() and status = 'pending')
    then return false; end if;
  select * into c from private.biz_codes where biz_id = p_id;
  if not found or c.expires_at < now() or c.attempts >= 5 then return false; end if;
  update private.biz_codes set attempts = attempts + 1 where biz_id = p_id;
  if c.code_hash = private.h(btrim(p_code)) then
    perform private.promote(p_id);
    return true;
  end if;
  return false;
end$$;

create or replace function public.directory_search(p_q text default null, p_cat text default null,
  p_city text default null, p_limit int default 30)
returns table(id uuid, name text, category text, city text, phone text)
language plpgsql stable security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'auth required'; end if;
  return query
    select d.id, d.name, d.category, d.city, d.phone from directory d
    where d.status = 'verified'
      and (p_cat is null or d.category = p_cat)
      and (p_city is null or d.city = p_city)
      and (p_q is null or p_q = '' or d.name ilike '%' || replace(replace(replace(p_q,'\','\\'),'%','\%'),'_','\_') || '%')
    order by d.verified_at desc
    limit least(greatest(p_limit, 1), 50);
end$$;

create or replace function public.my_businesses()
returns table(id uuid, name text, category text, city text, phone text, status text)
language sql stable security definer set search_path = public as $$
  select id, name, category, city, phone, status from directory where owner = auth.uid() order by created_at desc;
$$;

create or replace function public.delete_business(p_id uuid) returns void
language plpgsql security definer set search_path = public, private, extensions as $$
declare d directory%rowtype;
begin
  select * into d from directory where id = p_id and owner = auth.uid();
  if not found then return; end if;
  delete from businesses where num_hash = d.num_hash and owner = auth.uid();
  delete from directory where id = p_id;
end$$;

-- ---------- للإدارة / Edge Function فقط (service_role) ----------
create or replace function public.admin_store_code(p_id uuid, p_code text) returns text
language plpgsql security definer set search_path = public, private, extensions as $$
declare d directory%rowtype;
begin
  select * into d from directory where id = p_id and status = 'pending';
  if not found then raise exception 'not pending'; end if;
  if exists (select 1 from private.biz_codes where biz_id = p_id and sent_at > now() - interval '60 seconds')
    then raise exception 'cooldown'; end if;
  insert into private.biz_codes(biz_id, code_hash, expires_at) values (p_id, private.h(p_code), now() + interval '10 minutes')
  on conflict (biz_id) do update
    set code_hash = excluded.code_hash, expires_at = excluded.expires_at, attempts = 0, sent_at = now();
  return d.phone;
end$$;

-- توثيق يدوي من الإدارة (بديل عن SMS): select admin_verify_business('<id>');
create or replace function public.admin_verify_business(p_id uuid) returns void
language plpgsql security definer set search_path = public, private, extensions as $$
begin perform private.promote(p_id); end$$;

revoke execute on function public.business_register(text,text,text,text), public.business_verify(uuid,text),
  public.directory_search(text,text,text,int), public.my_businesses(), public.delete_business(uuid),
  public.admin_store_code(uuid,text), public.admin_verify_business(uuid) from public, anon, authenticated;
grant execute on function public.business_register(text,text,text,text), public.business_verify(uuid,text),
  public.directory_search(text,text,text,int), public.my_businesses(), public.delete_business(uuid) to authenticated;
grant execute on function public.admin_store_code(uuid,text), public.admin_verify_business(uuid) to service_role;
