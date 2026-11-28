import { access, readFile } from 'node:fs/promises';

await access('dist/index.html');
const [sourceHtml, distHtml, androidHtml, migration] = await Promise.all([
  readFile('index.html', 'utf8'),
  readFile('dist/index.html', 'utf8'),
  readFile('app/src/main/assets/public/index.html', 'utf8'),
  readFile('sql/02_nexus_order_security.sql', 'utf8')
]);

if (!distHtml.includes('<html') || !distHtml.includes('createClient')) {
  throw new Error('dist/index.html missing NEXUS app content');
}

if (sourceHtml !== distHtml || distHtml !== androidHtml) {
  throw new Error('NEXUS source, dist and Android bundled HTML files must be identical');
}

const requiredUiChecks = [
  ["protected order creation RPC", distHtml.includes("sb.rpc('nexus_create_order'")],
  ["protected order review RPC", distHtml.includes("sb.rpc('nexus_review_order'")],
  ["server-side subscription expiry RPC", distHtml.includes("sb.rpc('nexus_get_current_subscription'")],
  ["no direct order INSERT", !distHtml.includes("sb.from('orders').insert")],
  ["no direct order UPDATE", !distHtml.includes("sb.from('orders').update")],
  ["no legacy manual subscription grant", !distHtml.includes("nexus_grant_subscription")]
];

const requiredSqlChecks = [
  ["order creation RPC", migration.includes('create or replace function public.nexus_create_order')],
  ["atomic order review RPC", migration.includes('create or replace function public.nexus_review_order')],
  ["database-clock expiry check", migration.includes('s.ends_at > now()')],
  ["row lock during review", migration.includes('for update')],
  ["unique source-order index", migration.includes('subscriptions_source_order_uidx')],
  ["admin audit log", migration.includes('public.admin_audit_logs')],
  ["direct order writes revoked", migration.includes('revoke insert, update, delete on public.orders')],
  ["profile update restricted to display_name", migration.includes('grant update(display_name) on public.profiles')]
];

for (const [label, passed] of [...requiredUiChecks, ...requiredSqlChecks]) {
  if (!passed) throw new Error('NEXUS security build check failed: ' + label);
}

console.log('NEXUS web assets and security migration checks passed.');
