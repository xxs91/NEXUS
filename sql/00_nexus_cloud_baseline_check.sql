-- NEXUS Supabase cloud baseline inspection.
-- READ ONLY: this script does not create, update or delete any data or schema.
-- Run it in Supabase SQL Editor before applying sql/02_nexus_order_security.sql.

-- 1) Compare actual columns with sql/01_nexus_schema.sql.
select
  table_name,
  column_name,
  data_type,
  is_nullable,
  column_default
from information_schema.columns
where table_schema = 'public'
  and table_name in ('profiles', 'admin_users', 'plans', 'orders', 'subscriptions')
order by table_name, ordinal_position;

-- 2) Confirm which baseline tables currently have Row Level Security enabled.
select
  c.relname as table_name,
  c.relrowsecurity as rls_enabled,
  c.relforcerowsecurity as force_rls
from pg_class as c
join pg_namespace as n on n.oid = c.relnamespace
where n.nspname = 'public'
  and c.relname in ('profiles', 'admin_users', 'plans', 'orders', 'subscriptions')
  and c.relkind = 'r'
order by c.relname;

-- 3) List the actual policies. Compare them with the policy names and rules in
-- sql/01_nexus_schema.sql; do not assume the repository file was deployed.
select
  tablename,
  policyname,
  roles,
  cmd,
  qual,
  with_check
from pg_policies
where schemaname = 'public'
  and tablename in ('profiles', 'admin_users', 'plans', 'orders', 'subscriptions')
order by tablename, policyname;

-- 4) Confirm the currently installed helper functions and their signatures.
select
  p.proname as function_name,
  pg_get_function_identity_arguments(p.oid) as arguments,
  pg_get_function_result(p.oid) as result_type,
  p.prosecdef as security_definer
from pg_proc as p
join pg_namespace as n on n.oid = p.pronamespace
where n.nspname = 'public'
  and p.proname in ('nexus_is_admin', 'nexus_grant_subscription')
order by p.proname;
