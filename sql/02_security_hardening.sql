-- NEXUS security hardening migration 02
-- Run once in Supabase SQL Editor as project owner. Safe to re-run.

create table if not exists public.admin_audit_log (
 id bigint generated always as identity primary key, actor_user_id uuid,
 action text not null, entity_type text not null, entity_id text,
 details jsonb not null default '{}'::jsonb, created_at timestamptz not null default now()
);
alter table public.admin_audit_log enable row level security;
drop policy if exists "admin read audit log" on public.admin_audit_log;
create policy "admin read audit log" on public.admin_audit_log for select to authenticated using (public.nexus_is_admin());
revoke all on public.admin_audit_log from anon, authenticated;
grant select on public.admin_audit_log to authenticated;

create or replace function public.nexus_guard_profile_update()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
 if new.id <> old.id then raise exception 'profile identity is immutable'; end if;
 if new.account_status is distinct from old.account_status and not public.nexus_is_admin() then
  raise exception 'only administrators can change account status';
 end if;
 return new;
end; $$;
drop trigger if exists nexus_profile_guard on public.profiles;
create trigger nexus_profile_guard before update on public.profiles for each row execute function public.nexus_guard_profile_update();

create or replace function public.nexus_guard_order_write()
returns trigger language plpgsql security definer set search_path = '' as $$
declare v_price text;
begin
 if tg_op = 'INSERT' then
  if auth.uid() is null or (new.user_id <> auth.uid() and not public.nexus_is_admin()) then raise exception 'cannot create an order for another user'; end if;
  select price_label into v_price from public.plans where id=new.plan_id and is_active=true;
  if v_price is null then raise exception 'plan not found or inactive'; end if;
  new.amount_label := v_price; new.status := 'pending'; return new;
 end if;
 if new.id <> old.id or new.user_id <> old.user_id or new.plan_id <> old.plan_id
  or new.amount_label <> old.amount_label or new.created_at <> old.created_at then raise exception 'order details are immutable'; end if;
 if new.status is distinct from old.status and not public.nexus_is_admin() then raise exception 'only administrators can change order status'; end if;
 if new.status = 'approved' and old.status <> 'approved' and coalesce(current_setting('nexus.approving_order',true),'') <> 'on' then raise exception 'orders must be approved through nexus_approve_order'; end if;
 return new;
end; $$;
drop trigger if exists nexus_order_guard on public.orders;
create trigger nexus_order_guard before insert or update on public.orders for each row execute function public.nexus_guard_order_write();

create or replace function public.nexus_audit_admin_change()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
 if public.nexus_is_admin() then
  insert into public.admin_audit_log(actor_user_id,action,entity_type,entity_id,details)
  values(auth.uid(),tg_op,tg_table_name,case when tg_op='DELETE' then old.id::text else new.id::text end,jsonb_build_object('changed_at',now()));
 end if;
 if tg_op='DELETE' then return old; else return new; end if;
end; $$;
drop trigger if exists nexus_orders_audit on public.orders;
create trigger nexus_orders_audit after insert or update or delete on public.orders for each row execute function public.nexus_audit_admin_change();
drop trigger if exists nexus_subscriptions_audit on public.subscriptions;
create trigger nexus_subscriptions_audit after insert or update or delete on public.subscriptions for each row execute function public.nexus_audit_admin_change();

drop policy if exists "admin manage plans" on public.plans;
create policy "admin manage plans" on public.plans for all to authenticated using (public.nexus_is_admin()) with check (public.nexus_is_admin());
grant insert, update, delete on public.plans to authenticated;

create or replace function public.nexus_approve_order(p_order_id uuid)
returns uuid language plpgsql security definer set search_path = '' as $$
declare v_order public.orders%rowtype; v_subscription_id uuid;
begin
 if not public.nexus_is_admin() then raise exception 'admin access required'; end if;
 select * into v_order from public.orders where id=p_order_id for update;
 if not found then raise exception 'order not found'; end if;
 if v_order.status <> 'pending' then raise exception 'order is not pending'; end if;
 v_subscription_id := public.nexus_grant_subscription(v_order.user_id,v_order.plan_id);
 perform set_config('nexus.approving_order','on',true);
 update public.orders set status='approved' where id=p_order_id;
 return v_subscription_id;
end; $$;
revoke all on function public.nexus_approve_order(uuid) from public;
grant execute on function public.nexus_approve_order(uuid) to authenticated;

create or replace function public.nexus_expire_subscriptions()
returns integer language plpgsql security definer set search_path = '' as $$
declare v_count integer;
begin
 if not public.nexus_is_admin() then raise exception 'admin access required'; end if;
 update public.subscriptions set status='expired' where status='active' and ends_at <= now();
 get diagnostics v_count = row_count; return v_count;
end; $$;
revoke all on function public.nexus_expire_subscriptions() from public;
grant execute on function public.nexus_expire_subscriptions() to authenticated;
