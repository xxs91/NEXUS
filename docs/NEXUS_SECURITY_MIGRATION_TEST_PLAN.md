# NEXUS V1 安全迁移测试清单

> 状态：待在 Supabase 测试项目执行。本文件不是测试通过证明。
> 配套迁移：`sql/02_nexus_order_security.sql`
> **不要直接在生产项目执行。** 先确认 `sql/01_nexus_schema.sql` 与实际云端表结构一致，并完成数据库备份。

## 1. 执行前确认

- [ ] 在 Supabase 控制台确认当前项目正确，避免误操作其他项目。
- [ ] 在 SQL Editor 检查 `profiles`、`plans`、`orders`、`subscriptions`、`admin_users` 是否存在。
- [ ] 对照 `sql/01_nexus_schema.sql` 核对列名、类型、约束和现有 RLS policy。
- [ ] 确认 `orders.plan_id` 能关联到 `plans.id`，且历史订单对应的套餐记录仍存在。
- [ ] 备份测试项目；不要在本阶段导入真实用户或真实订单。
- [ ] 先在独立 Supabase 测试项目执行迁移，再做下面的身份测试。

## 2. 迁移后结构检查

在 Supabase SQL Editor 执行以下只读检查：

```sql
select table_name, column_name, data_type
from information_schema.columns
where table_schema = 'public'
  and table_name in ('orders', 'subscriptions', 'admin_audit_logs')
order by table_name, ordinal_position;

select
  to_regprocedure('public.nexus_create_order(uuid)') as create_order_function,
  to_regprocedure('public.nexus_review_order(uuid,text,text)') as review_order_function,
  to_regprocedure('public.nexus_get_current_subscription()') as current_subscription_function,
  to_regprocedure('public.nexus_grant_subscription(uuid,uuid)') as legacy_grant_function;

select
  has_column_privilege('authenticated', 'public.profiles', 'display_name', 'UPDATE')
    as can_update_display_name,
  has_column_privilege('authenticated', 'public.profiles', 'account_status', 'UPDATE')
    as can_update_account_status,
  has_table_privilege('authenticated', 'public.orders', 'INSERT')
    as can_insert_orders_directly,
  has_table_privilege('authenticated', 'public.orders', 'UPDATE')
    as can_update_orders_directly,
  has_table_privilege('authenticated', 'public.subscriptions', 'INSERT')
    as can_insert_subscriptions_directly;
```

预期结果：

- `orders` 有 `order_no`、`plan_name_snapshot`、`duration_days_snapshot`、`reviewed_by`、`reviewed_at`、`admin_note`。
- `subscriptions` 有 `source_order_id`、`granted_by`。
- `admin_audit_logs` 表存在并启用 RLS。
- `nexus_create_order`、`nexus_review_order`、`nexus_get_current_subscription` 三个新 RPC 均存在；旧的 `nexus_grant_subscription(uuid,uuid)` 不再允许 authenticated 执行。
- `can_update_display_name = true`；`can_update_account_status = false`。
- `can_insert_orders_directly = false`、`can_update_orders_directly = false`、`can_insert_subscriptions_directly = false`。

## 3. 使用三个测试身份

准备两个普通测试账户 A、B，以及一个由项目所有者通过受控方式加入 `admin_users` 的管理员测试账户。不要使用真实客户账户。

### 普通用户测试

- [ ] A 登录后能读取自己的资料、订单和订阅。
- [ ] A 能修改自己的昵称。
- [ ] A 不能修改自己的 `account_status`。
- [ ] A 通过套餐页面提交订单后，订单状态为 `pending`。
- [ ] 新订单的套餐名称、期限、价格展示快照由数据库从当前有效套餐读取。
- [ ] A 不能直接 INSERT/UPDATE `orders`，不能直接 INSERT/UPDATE `subscriptions`。
- [ ] A 不能读取 B 的资料、订单或订阅。
- [ ] B 不能读取 A 的资料、订单或订阅。

### 管理员审核测试

- [ ] 管理员能查看待处理订单。
- [ ] 管理员点击“通过并开通”后，订单变为 `approved`，同时生成一条关联该订单的订阅。
- [ ] 对同一订单再次审核会失败，且不会生成第二条订阅。
- [ ] 管理员拒绝另一笔待处理订单后，订单变为 `rejected`，不会生成订阅。
- [ ] `admin_audit_logs` 中能查到对应的审核动作、操作者和目标订单。
- [ ] 普通用户不能调用管理员审核 RPC 成功，也不能读取审计日志。

### 到期显示测试

- [ ] 在测试环境为测试用户准备一条 `ends_at` 已早于当前时间的订阅。
- [ ] 客户端不再把该订阅显示为有效。
- [ ] `nexus_get_current_subscription()` 使用数据库的 `now()` 同时检查 `starts_at <= now()` 和 `ends_at > now()`；改变测试设备的系统时间不会改变服务端返回结果。
- [ ] 尚未接入支付时，页面仍只显示“订单申请/待审核”，不得显示“已付款”。

## 4. 当前限制

- 这份迁移是基于仓库中 `sql/01_nexus_schema.sql` 的静态结构编写，尚未连接或执行到用户的 Supabase 云项目。
- 迁移不代表数据库已部署，也不代表上述测试已通过。
- 套餐管理、用户停用、取消订单、支付和自动续费不在本次迁移内。
- 如果云端实际结构与基线 SQL 不同，先停止执行并重新核对迁移；不要通过删除旧表或清空数据来“修复”差异。
