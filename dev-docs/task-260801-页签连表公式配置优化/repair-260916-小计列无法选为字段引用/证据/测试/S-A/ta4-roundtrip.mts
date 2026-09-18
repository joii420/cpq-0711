// T-A4 · AC-13 · 存量页签公式往返不变（只读）
// 断言来源：问题说明.md ⑥ AC-13
//   ① 4.5 所列 16 处 token，本分支往返（回显→解析）后与库中存储逐字段相等
//   ② 15 处 component_subtotal 的回显文字均带 (小计)
//   ③ 其余 token，本分支往返结果与 master 往返结果逐字段相等
//   ④ 前置非空：参与比对的公式数 ≥ 1、16 处全部命中（打印实际数量）
// 数据：cpq_db_0724 只读（PGOPTIONS 强制只读事务）；tabDefs 取共享后端 8081 GET /api/cpq/components/{id}/tab-defs。
// 共库片：不做全局计数断言；16 处按「宿主编号+公式名+被引页签+列」逐项点名核对（清单来自 scan-输出-260916.txt）。
//
// 运行（在 worktree 内；node fetch 不走 http_proxy，仍显式去掉以防 NODE_USE_ENV_PROXY）：
//   D=<T>/证据/离线判决; S=<T>/证据/测试/S-A
//   env -u http_proxy -u HTTP_PROXY -u https_proxy -u HTTPS_PROXY -u NODE_USE_ENV_PROXY \
//     node --import $D/hook.mjs $S/ta4-roundtrip.mts | tee $S/输出/ta4-$(date +%y%m%d-%H%M%S).txt ; echo "exit=${PIPESTATUS[0]}"
// 会话：优先 env SA_SESSION（CPQ_SESSION cookie 值）；否则读 worktree 与主工作区的 e2e/.auth/admin.json。
//       401 时 exit=3，不做登录（登录是 POST，S-A 无此权限），请主线提供有效会话。
// 证伪（每项期望 exit=1）：
//   SA_BRANCH_ROOT=/home/joii/project/cpq  → 本分支位置换成 master 函数：② 应 15 处 FAIL
//   SA_FALSIFY=item                         → 篡改本分支往返结果中的 16 处（删 label / 改 value）：① 应 16 处 FAIL
//   SA_FALSIFY=other                        → 篡改 master 往返结果中第一个非 16 处 token：③ 应至少 1 个公式 FAIL
import { execFileSync } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import {
  WT, MAIN, SERIALIZE_REL, check, warn, precondition, finish,
  canon, same, firstDiff, walk, getAt, setAt, clone, countOf, assertMainIsMaster, branchProvenance, type Path,
} from './sa-common.mts';

const API = process.env.SA_API ?? 'http://localhost:8081';
const BRANCH_ROOT = process.env.SA_BRANCH_ROOT ?? WT;
const FALSIFY = process.env.SA_FALSIFY ?? '';
const CODES = ['COMP-0001', 'COMP-0002', 'COMP-0003', 'COMP-0009', 'COMP-2506'];
// 问题说明 4.5 / scan-输出-260916.txt（采样 2026-09-17T05:49:57Z）逐条
const EXPECTED_16: string[] = [
  'A|COMP-0001|管理费公式|COMP-0002|回收成本',
  'A|COMP-0001|公式3|COMP-0002|回收成本',
  'A|COMP-0003|税后单价公式|COMP-0001|税率',
  'A|COMP-0009|小计1|COMP-0002|材料成本',
  'A|COMP-0009|小计1|COMP-0002|材料损耗成本',
  'A|COMP-0009|小计1|COMP-0002|回收成本',
  'A|COMP-0009|小计1|COMP-0001|管理费',
  'A|COMP-0002|非银点类材料成本公式|COMP-0004|加工费',
  'A|COMP-0002|非银点类材料成本公式|COMP-0001|税率',
  'A|COMP-0002|回收成本|COMP-0004|加工费',
  'A|COMP-0002|回收成本|COMP-0001|税率',
  'A|COMP-0002|铆钉额外费用|COMP-0001|税率',
  'A|COMP-0002|铆钉额外费用|COMP-0004|加工费',
  'A|COMP-0002|银点1.0|COMP-0001|税率',
  'A|COMP-0002|零件材料成本|COMP-0004|加工费',
  'B|COMP-2506|公式1|COMP-2507|材质成本',
];

console.log(`# T-A4 AC-13  at=${new Date().toISOString()}  api=${API}  branchRoot=${BRANCH_ROOT}  falsify='${FALSIFY}'`);
assertMainIsMaster(SERIALIZE_REL);
if (BRANCH_ROOT === WT) branchProvenance(); else warn(`证伪模式：本分支位置使用 ${BRANCH_ROOT}`);
const B = await import(`${BRANCH_ROOT}/${SERIALIZE_REL}`);
const M = await import(`${MAIN}/${SERIALIZE_REL}`);
precondition(typeof B.tokensToDrawerExpression === 'function' && typeof M.expressionToTokens === 'function', '两份 formulaSerialize 均可导入');

// ---------- DB（只读） ----------
function sql(q: string): string {
  return execFileSync('psql', ['-h', '10.177.152.12', '-U', 'postgres', '-d', 'cpq_db_0724', '-At', '-v', 'ON_ERROR_STOP=1', '-c', q], {
    encoding: 'utf8', maxBuffer: 64 * 1024 * 1024,
    env: { ...process.env, PGPASSWORD: 'joii5231', PGOPTIONS: '-c default_transaction_read_only=on' },
  }).trim();
}
const sampledAt = sql('select now()');
console.log(`[db] cpq_db_0724 只读采样时刻 ${sampledAt}`);
const hosts: any[] = sql(`select json_build_object('id',id,'code',code,'name',name,'fields',fields,'formulas',formulas,'rk',row_key_fields)
  from component where code in (${CODES.map((c) => `'${c}'`).join(',')}) order by code`).split('\n').filter(Boolean).map((l) => JSON.parse(l));
for (const c of CODES) precondition(hosts.filter((h) => h.code === c).length === 1, `库中 ${c} 恰 1 行（实际 ${hosts.filter((h) => h.code === c).length}）`);
// 被 cross_tab_ref 引用的源组件（判「按行引用小计列」需要其字段的小计标记，与 scan_subtotal_refs.py 同口径）
const srcIds = new Set<string>();
for (const h of hosts) for (const f of h.formulas ?? []) for (const { token } of walk(f.expression ?? [])) if (token.type === 'cross_tab_ref' && token.source) srcIds.add(token.source);
for (const s of srcIds) precondition(/^[0-9a-f-]{36}$/i.test(s), `source id 形如 uuid：${s}`);
const srcs: any[] = srcIds.size ? sql(`select json_build_object('id',id,'code',code,'fields',fields) from component
  where id::text in (${[...srcIds].map((s) => `'${s}'`).join(',')})`).split('\n').filter(Boolean).map((l) => JSON.parse(l)) : [];
const srcById = new Map(srcs.map((s) => [s.id, s]));
const subCols = (c: any) => new Set((c?.fields ?? []).filter((f: any) => f.is_subtotal === true || f.is_subtotal === 'true').map((f: any) => f.name));

// ---------- HTTP（只 GET） ----------
function session(): string {
  if (process.env.SA_SESSION) return process.env.SA_SESSION;
  for (const p of [`${WT}/cpq-frontend/e2e/.auth/admin.json`, `${MAIN}/cpq-frontend/e2e/.auth/admin.json`]) {
    if (!existsSync(p)) continue;
    const c = JSON.parse(readFileSync(p, 'utf8')).cookies?.find((x: any) => x.name === 'CPQ_SESSION');
    if (c?.value) { console.log(`[api] 会话取自 ${p}`); return c.value; }
  }
  return '';
}
const COOKIE = `CPQ_SESSION=${session()}`;
async function GET(path: string): Promise<any> {
  const r = await fetch(API + path, { method: 'GET', headers: { Cookie: COOKIE, Accept: 'application/json' } });
  if (r.status === 401) { console.log(`ABORT: GET ${path} → 401，会话无效；请主线提供有效 CPQ_SESSION（SA_SESSION=...）`); process.exit(3); }
  const body = await r.text();
  precondition(r.status === 200, `GET ${path} → ${r.status}`);
  return JSON.parse(body);
}
function findKey(o: any, key: string, depth = 0): any {
  if (!o || typeof o !== 'object' || depth > 3) return undefined;
  if (key in o) return o[key];
  for (const v of Object.values(o)) { const r = findKey(v, key, depth + 1); if (r !== undefined) return r; }
  return undefined;
}

// ---------- 主循环 ----------
type Item = { key: string; host: string; fname: string; path: Path; token: any; kind: 'A' | 'B' };
const items: Item[] = [];
let formulaCount = 0, itemFailA = 0, itemFailB = 0, otherFail = 0;

for (const h of hosts) {
  // 验明正身：接口返回的公式与只读库一致 ⇒ tab-defs 来自同一份数据
  const comp = await GET(`/api/cpq/components/${h.id}`);
  const apiFormulas = findKey(comp, 'formulas');
  precondition(Array.isArray(apiFormulas), `${h.code} 接口响应含 formulas`);
  const sameData = same(apiFormulas.map((f: any) => f.expression), (h.formulas ?? []).map((f: any) => f.expression));
  precondition(sameData, `${h.code} 接口 formulas 与 cpq_db_0724 逐字段一致（不一致=后端不是这个库或采样期间被改）${sameData ? '' : '：' + firstDiff(apiFormulas.map((f: any) => f.expression), (h.formulas ?? []).map((f: any) => f.expression))}`);
  const tdResp = await GET(`/api/cpq/components/${h.id}/tab-defs`);
  const tabDefs: any[] = Array.isArray(tdResp) ? tdResp : (findKey(tdResp, 'tabDefs') ?? (Array.isArray(tdResp?.data) ? tdResp.data : undefined));
  precondition(Array.isArray(tabDefs) && tabDefs.length > 0, `${h.code} tab-defs 非空（顶层键：${Array.isArray(tdResp) ? 'array' : Object.keys(tdResp ?? {}).join(',')}；条数 ${tabDefs?.length}）`);
  // 抽屉传入的是组件自身行键 component.row_key_fields（主线审核 260917 第 2 点），不依赖 tab-defs 的 self 项
  const selfRK: string[] = Array.isArray(h.rk) ? h.rk : [];
  const tdSelf = tabDefs.find((t) => t.self)?.rowKeyFields;
  if (tdSelf && !same(tdSelf, selfRK)) warn(`${h.code} tab-defs self.rowKeyFields=${JSON.stringify(tdSelf)} 与 row_key_fields=${JSON.stringify(h.rk)} 不一致（以后者为准）`);
  if (h.rk != null && !Array.isArray(h.rk)) warn(`${h.code} row_key_fields 非数组：${JSON.stringify(h.rk)}，按空行键处理`);
  console.log(`\n=== ${h.code} ${h.name} id=${h.id} 公式 ${h.formulas?.length ?? 0} 个 tabDefs ${tabDefs.length} 条 selfRowKeyFields=${JSON.stringify(selfRK)}`);

  for (const f of h.formulas ?? []) {
    const expr = f.expression;
    if (!Array.isArray(expr) || expr.length === 0) { warn(`${h.code}「${f.name}」expression 非数组或为空，跳过`); continue; }
    formulaCount++;
    const mine: Item[] = [];
    for (const { path, token: t } of walk(expr)) {
      if (t.type === 'component_subtotal' && !t.is_tab_total && t.value && t.value !== '__amount_total__')
        mine.push({ key: `A|${h.code}|${f.name}|${t.component_code}|${t.value}`, host: h.code, fname: f.name, path, token: t, kind: 'A' });
      if (t.type === 'cross_tab_ref' && t.target && !t.targetExpr && subCols(srcById.get(t.source)).has(t.target))
        mine.push({ key: `B|${h.code}|${f.name}|${srcById.get(t.source)?.code}|${t.target}`, host: h.code, fname: f.name, path, token: t, kind: 'B' });
    }
    items.push(...mine);

    const rt = (mod: any, tag: string) => {
      try {
        const text: string = mod.tokensToDrawerExpression(expr, tabDefs, h.id);
        const back: any[] = mod.expressionToTokens(text, tabDefs, selfRK, h.id);
        return { text, back, err: '' };
      } catch (e: any) { return { text: '', back: [] as any[], err: `${tag} 往返抛错：${e?.message}` }; }
    };
    const b = rt(B, 'branch'), m = rt(M, 'master');
    if (FALSIFY === 'item') for (const it of mine) { const t = getAt(b.back, it.path); if (t) { delete t.label; t.value = `${t.value ?? ''}#X`; t.target = t.target ? `${t.target}#X` : t.target; } }
    console.log(`--- 「${f.name}」 branch: ${b.err || b.text}\n    master: ${m.err || m.text}`);
    check(`AC-13 往返可执行 ${h.code}「${f.name}」`, !b.err, b.err || 'branch 往返无异常');

    // ① ② 逐项
    for (const it of mine) {
      const got = getAt(b.back, it.path);
      const ok1 = !b.err && same(it.token, got);
      check(`AC-13① ${it.key}`, ok1, ok1 ? `往返后逐字段相等 ${canon(got)}` : `库=${canon(it.token)} 往返=${canon(got)} diff=${firstDiff(it.token, got)}`);
      if (!ok1) it.kind === 'A' ? itemFailA++ : itemFailB++;
      if (it.kind === 'A') {
        const tab = tabDefs.find((t) => t.alias === it.token.component_code || t.componentId === it.token.component_code)?.componentName
          ?? String(it.token.label ?? '').split('·')[0];
        const want = `[${tab}.${it.token.value}(小计)]`;
        const need = mine.filter((x) => x.kind === 'A' && x.token.component_code === it.token.component_code && x.token.value === it.token.value).length;
        const have = countOf(b.text, want);
        check(`AC-13② ${it.key}`, have >= need, `回显含 ${want} ${have} 次（该公式同引用 ${need} 处）`);
      }
    }
    // ③ 其余 token：branch 往返 vs master 往返（遮住 16 处）
    if (m.err) { check(`AC-13③ ${h.code}「${f.name}」`, false, `master 往返失败，无法对照：${m.err}`); otherFail++; continue; }
    if (b.err) { check(`AC-13③ ${h.code}「${f.name}」`, false, 'branch 往返失败，无法对照'); otherFail++; continue; }
    const bb = clone(b.back), mm = clone(m.back);
    if (FALSIFY === 'other') {
      const masked = new Set(mine.map((x) => x.path.join('/')));
      const victim = [...walk(mm)].find(({ path }) => !masked.has(path.join('/')));
      if (victim) victim.token.__falsified = true;
    }
    for (const it of mine) { setAt(bb, it.path, '<ITEM>'); setAt(mm, it.path, '<ITEM>'); }
    const ok3 = same(bb, mm);
    check(`AC-13③ ${h.code}「${f.name}」`, ok3, ok3 ? `其余 token 一致（token 数 ${b.back.length}）` : `diff=${firstDiff(bb, mm)}`);
    if (!ok3) otherFail++;
    if (!same(b.back, expr)) console.log(`    INFO 本分支往返整式 ≠ 库存储：${firstDiff(expr, b.back)}`);
  }
}

// ---------- ④ 前置非空 + 16 处点名 ----------
const found = items.map((x) => x.key).sort();
const want = [...EXPECTED_16].sort();
const missing = want.filter((k) => { const i = found.indexOf(k); if (i < 0) return true; found.splice(i, 1); return false; });
console.log(`\n参与比对公式数=${formulaCount}  识别到引用=${items.length}（A=${items.filter((x) => x.kind === 'A').length} B=${items.filter((x) => x.kind === 'B').length}）`);
check('AC-13④ 公式数≥1', formulaCount >= 1, `实际 ${formulaCount}`);
check('AC-13④ 16 处全部命中', missing.length === 0, `缺 ${missing.length}：${JSON.stringify(missing)}；清单外多出 ${found.length}：${JSON.stringify(found)}`);
console.log(`逐项小结：①失败 A=${itemFailA} B=${itemFailB}；③失败公式=${otherFail}`);
finish();
