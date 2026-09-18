// S-C 共用：登录、请求、落盘、库查询（只连一次性库）
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
export const BACKEND = process.env.RP_BACKEND || 'http://localhost:8293';
if (!/^http:\/\/localhost:8293$/.test(BACKEND)) throw new Error(`S-C 只允许打 8293，当前 ${BACKEND}`);
export const S = path.dirname(fileURLToPath(import.meta.url));
let cookie = '';
export let FAILS = 0;
export const pass = (m) => console.log('✅ PASS:', m);
export const fail = (m) => { FAILS++; console.log('❌ FAIL:', m); };
export const check = (ok, m) => (ok ? pass(m) : fail(m));
export function sql(q) {
  return execFileSync('psql', ['-h', '10.177.152.12', '-U', 'postgres', '-d', 'cpq_db_rp0916d', '-v', 'ON_ERROR_STOP=1', '-At', '-F', '|', '-c', q],
    { env: { ...process.env, PGPASSWORD: 'joii5231' }, encoding: 'utf-8' }).trim();
}
export function guardDb() {
  const c = sql("SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()");
  if (!c.startsWith('repair-260916-subtotal-suffix 一次性库（第二个')) throw new Error('库身份不符: ' + c);
  console.log('[guard] 库身份 OK', new Date().toISOString());
}
export async function login() {
  const r = await fetch(`${BACKEND}/api/cpq/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'Admin@2026' }) });
  if (!r.ok) throw new Error(`登录失败 ${r.status} ${await r.text()}`);
  cookie = r.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ');
}
/** 返回 {status, text, json}；不抛错，调用方断言 */
export async function api(p, init = {}) {
  const r = await fetch(`${BACKEND}${p}`, { ...init, headers: { Cookie: cookie, 'Content-Type': 'application/json', ...(init.headers || {}) } });
  const text = await r.text();
  let json = null; try { json = JSON.parse(text); } catch { /* raw */ }
  return { status: r.status, text, json };
}
export function outDir(name) { const d = path.join(S, name); fs.mkdirSync(d, { recursive: true }); return d; }
export function save(dir, name, obj) { fs.writeFileSync(path.join(dir, name), typeof obj === 'string' ? obj : JSON.stringify(obj, null, 2)); }
export const unwrap = (res) => (res.json && 'data' in res.json ? res.json.data : res.json);
