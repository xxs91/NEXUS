-- NEXUS database schema. Run in Supabase SQL Editor as project owner.
create extension if not exists pgcrypto;

create table if not exists public.profiles (
 id uuid primary key references auth.users(id) on delete cascade,
 created_at timestamptz not null default now(),
 display_name text not null default '',
 account_status text not null default 'active' check (account_status in ('active','suspended'))
);

create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
 insert into public.profiles(id,display_name,account_status)
 values(new.id,coalesce(new.raw_user_meta_data->>'display_name',''),'active')
 on conflict(id) do nothing;
 return new;
end; $$;
drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created after insert on auth.users for each row execute procedure public.handle_new_user();

create table if not exists public.admin_users(user_id uuid primary key references auth.users(id) on delete cascade, created_at timestamptz not null default now());
create table if not exists public.plans(
 id uuid primary key default gen_random_uuid(), name text not null, description text not null default '',
 price_label text not null default '价格待定', duration_days integer not null check(duration_days>0),
 duration_label text not null default '', is_active boolean not null default true, sort_order integer not null default 0,
 created_at timestamptz not null default now()
);
create table if not exists public.orders(
 id uuid primary key default gen_random_uuid(), user_id uuid not null references auth.users(id) on delete cascade,
 plan_id uuid not null references public.plans(id), amount_label text not null default '',
 status text not null default 'pending' check(status in ('pending','approved','rejected','cancelled')),
 created_at timestamptz not null default now()
);
create table if not exists public.subscriptions(
 id uuid primary key default gen_random_uuid(), user_id uuid not null references auth.users(id) on delete cascade,
 plan_id uuid not null references public.plans(id), status text not null default 'active' check(status in ('active','expired','cancelled')),
 starts_at timestamptz not null default now(), ends_at timestamptz not null,
 created_at timestamptz not null default now()
);

create or replace function public.nexus_is_admin() returns boolean
language sql stable security definer set search_path = '' as $$
 select exists(select 1 from public.admin_users where user_id = (select auth.uid()));
$$;
create or replace function public.nexus_grant_subscription(p_user_id uuid,p_plan_id uuid) returns uuid
language plpgsql security definer set search_path = '' as $$
declare v_days integer; v_id uuid;
begin
 if not public.nexus_is_admin() then raise exception 'admin access required'; end if;
 select duration_days into v_days from public.plans where id=p_plan_id and is_active=true;
 if v_days is null then raise exception 'plan not found or inactive'; end if;
 insert into public.subscriptions(user_id,plan_id,status,starts_at,ends_at)
 values(p_user_id,p_plan_id,'active',now(),now()+make_interval(days=>v_days)) returning id into v_id;
 return v_id;
end; $$;

alter table public.profiles enable row level security;
alter table public.admin_users enable row level security;
alter table public.plans enable row level security;
alter table public.orders enable row level security;
alter table public.subscriptions enable row level security;

drop policy if exists "profile read own" on public.profiles;
create policy "profile read own" on public.profiles for select to authenticated using(id=(select auth.uid()) or public.nexus_is_admin());
drop policy if exists "profile update own" on public.profiles;
create policy "profile update own" on public.profiles for update to authenticated using(id=(select auth.uid())) with check(id=(select auth.uid()));
drop policy if exists "admin list admin users" on public.admin_users;
create policy "admin list admin users" on public.admin_users for select to authenticated using(user_id=(select auth.uid()));
drop policy if exists "plans visible" on public.plans;
create policy "plans visible" on public.plans for select to anon,authenticated using(is_active=true or public.nexus_is_admin());
drop policy if exists "orders own or admin read" on public.orders;
create policy "orders own or admin read" on public.orders for select to authenticated using(user_id=(select auth.uid()) or public.nexus_is_admin());
drop policy if exists "orders own create" on public.orders;
create policy "orders own create" on public.orders for insert to authenticated with check(user_id=(select auth.uid()));
drop policy if exists "admin update orders" on public.orders;
create policy "admin update orders" on public.orders for update to authenticated using(public.nexus_is_admin()) with check(public.nexus_is_admin());
drop policy if exists "subscriptions own or admin read" on public.subscriptions;
create policy "subscriptions own or admin read" on public.subscriptions for select to authenticated using(user_id=(select auth.uid()) or public.nexus_is_admin());
revoke all on function public.nexus_grant_subscription(uuid,uuid) from public;
grant execute on function public.nexus_grant_subscription(uuid,uuid) to authenticated;
grant execute on function public.nexus_is_admin() to authenticated;

grant select on public.plans to anon,authenticated;
grant select,update on public.profiles to authenticated;
grant select,insert,update on public.orders to authenticated;
grant select on public.subscriptions to authenticated;
grant select on public.admin_users to authenticated;

-- Seed example plans. Edit prices/terms before real operation.
insert into public.plans(name,description,price_label,duration_days,duration_label,sort_order)
select '基础版','基础数字服务订阅示例','价格待定',30,'30 天',1
where not exists(select 1 from public.plans);

-- IMPORTANT: after signing up, replace the UUID below with YOUR auth.users UUID,
-- then run this statement manually to grant admin rights to your own account:
-- insert into public.admin_users(user_id) values ('YOUR-USER-UUID') on conflict do nothing;
