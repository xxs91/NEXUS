-- NEXUS V1 security hardening and atomic order review.
-- Apply ONLY after confirming that sql/01_nexus_schema.sql is the deployed baseline
-- and after taking a database backup. Test on a staging project first.
-- This migration does not enable payments or any network-access functionality.

begin;

-- Preserve the values shown to the user at the time the order was created.
alter table public.orders
  add column if not exists order_no text,
  add column if not exists plan_name_snapshot text,
  add column if not exists duration_days_snapshot integer,
  add column if not exists reviewed_by uuid references auth.users(id) on delete set null,
  add column if not exists reviewed_at timestamptz,
  add column if not exists admin_note text;

update public.orders as o
set
  order_no = coalesce(
    o.order_no,
    'NX-' || to_char(o.created_at, 'YYYYMMDD') || '-' ||
      upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 10))
  )
where o.order_no is null;

update public.orders as o
set
  plan_name_snapshot = coalesce(o.plan_name_snapshot, p.name),
  duration_days_snapshot = coalesce(o.duration_days_snapshot, p.duration_days)
from public.plans as p
where p.id = o.plan_id
  and (o.plan_name_snapshot is null or o.duration_days_snapshot is null);

alter table public.orders
  alter column order_no set default (
    'NX-' || to_char(now(), 'YYYYMMDD') || '-' ||
    upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 10))
  ),
  alter column order_no set not null,
  alter column plan_name_snapshot set not null,
  alter column duration_days_snapshot set not null;

create unique index if not exists orders_order_no_uidx
  on public.orders(order_no);

-- Tie each granted subscription to at most one reviewed order.
alter table public.subscriptions
  add column if not exists source_order_id uuid
    references public.orders(id) on delete set null,
  add column if not exists granted_by uuid
    references auth.users(id) on delete set null;

create unique index if not exists subscriptions_source_order_uidx
  on public.subscriptions(source_order_id)
  where source_order_id is not null;

-- Minimal, append-only record of privileged actions.
create table if not exists public.admin_audit_logs (
  id uuid primary key default gen_random_uuid(),
  actor_user_id uuid not null references auth.users(id) on delete restrict,
  action text not null,
  target_type text not null,
  target_id uuid,
  details jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

alter table public.admin_audit_logs enable row level security;

drop policy if exists "admin audit read" on public.admin_audit_logs;
create policy "admin audit read"
  on public.admin_audit_logs
  for select
  to authenticated
  using (public.nexus_is_admin());

-- Remove direct client-side writes. The application must use the RPCs below.
drop policy if exists "orders own create" on public.orders;
drop policy if exists "admin update orders" on public.orders;

revoke insert, update, delete on public.orders from anon, authenticated;
grant select on public.orders to authenticated;

revoke update on public.profiles from authenticated;
grant update(display_name) on public.profiles to authenticated;

revoke insert, update, delete on public.subscriptions from anon, authenticated;
grant select on public.subscriptions to authenticated;

revoke all on public.admin_audit_logs from anon, authenticated;
grant select on public.admin_audit_logs to authenticated;

-- Users create orders using a plan ID only. The database supplies the user,
-- amount label, plan snapshots, order number and pending status.
create or replace function public.nexus_create_order(p_plan_id uuid)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_plan_name text;
  v_price_label text;
  v_duration_days integer;
  v_order_id uuid;
begin
  if v_user_id is null then
    raise exception 'authentication required';
  end if;

  select p.name, p.price_label, p.duration_days
    into v_plan_name, v_price_label, v_duration_days
  from public.plans as p
  where p.id = p_plan_id
    and p.is_active = true;

  if not found then
    raise exception 'plan not found or inactive';
  end if;

  insert into public.orders (
    user_id,
    plan_id,
    amount_label,
    status,
    plan_name_snapshot,
    duration_days_snapshot
  )
  values (
    v_user_id,
    p_plan_id,
    coalesce(v_price_label, '价格待定'),
    'pending',
    v_plan_name,
    v_duration_days
  )
  returning id into v_order_id;

  return v_order_id;
end;
$$;

-- Approval/rejection, subscription creation and audit logging happen in one
-- database transaction. A row lock plus the pending-state check prevents
-- double approval; source_order_id has an additional unique constraint.
create or replace function public.nexus_review_order(
  p_order_id uuid,
  p_decision text,
  p_admin_note text default null
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_actor uuid := auth.uid();
  v_order public.orders%rowtype;
  v_subscription_id uuid := null;
  v_now timestamptz := now();
begin
  if v_actor is null or not public.nexus_is_admin() then
    raise exception 'admin access required';
  end if;

  if p_decision is null or p_decision not in ('approved', 'rejected') then
    raise exception 'decision must be approved or rejected';
  end if;

  select *
    into v_order
  from public.orders
  where id = p_order_id
  for update;

  if not found then
    raise exception 'order not found';
  end if;

  if v_order.status <> 'pending' then
    raise exception 'order is no longer pending';
  end if;

  if p_decision = 'approved' then
    if v_order.duration_days_snapshot is null
       or v_order.duration_days_snapshot <= 0 then
      raise exception 'order has an invalid duration snapshot';
    end if;

    insert into public.subscriptions (
      user_id,
      plan_id,
      status,
      starts_at,
      ends_at,
      source_order_id,
      granted_by
    )
    values (
      v_order.user_id,
      v_order.plan_id,
      'active',
      v_now,
      v_now + make_interval(days => v_order.duration_days_snapshot),
      v_order.id,
      v_actor
    )
    returning id into v_subscription_id;
  end if;

  update public.orders
  set
    status = p_decision,
    reviewed_by = v_actor,
    reviewed_at = v_now,
    admin_note = nullif(left(trim(coalesce(p_admin_note, '')), 500), '')
  where id = v_order.id;

  insert into public.admin_audit_logs (
    actor_user_id,
    action,
    target_type,
    target_id,
    details
  )
  values (
    v_actor,
    'order.' || p_decision,
    'order',
    v_order.id,
    jsonb_build_object(
      'subscription_id', v_subscription_id,
      'plan_id', v_order.plan_id,
      'duration_days', v_order.duration_days_snapshot
    )
  );

  return v_subscription_id;
end;
$$;

-- Retire the older grant function: it could create subscriptions without a
-- reviewed order and therefore bypass the atomic workflow.
revoke all on function public.nexus_grant_subscription(uuid, uuid)
  from public, anon, authenticated;

revoke all on function public.nexus_is_admin()
  from public, anon;
grant execute on function public.nexus_is_admin()
  to authenticated;

revoke all on function public.nexus_create_order(uuid)
  from public, anon;
grant execute on function public.nexus_create_order(uuid)
  to authenticated;

revoke all on function public.nexus_review_order(uuid, text, text)
  from public, anon;
grant execute on function public.nexus_review_order(uuid, text, text)
  to authenticated;

commit;
