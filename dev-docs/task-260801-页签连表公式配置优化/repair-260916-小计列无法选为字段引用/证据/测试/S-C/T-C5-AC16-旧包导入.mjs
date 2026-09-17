// T-C5（AC-16）旧导出包导入兼容
//   node T-C5-AC16-旧包导入.mjs run              # 分支后端(8293)：①②③ + ④分支侧预览快照(ab-branch.json)
//   node T-C5-AC16-旧包导入.mjs ab master         # master 后端(同端口 8293，连同一次性库)：④ master 侧预览快照（只调预览，不写库）
//   node T-C5-AC16-旧包导入.mjs compare          # ④ 比对 ab-branch.json 与 ab-master.json（④ 正式证据=接口侧；界面侧 ab-ui-*.json 仅参考）
// AC-16⑤（D-7）的界面断言在 e2e/repair260916-excel-migration.spec.ts，依赖本脚本 run 产出的 out-C5/导出-fromv1.1.json（1.2 包）
// 环境：IGNORE_UNBOUND=1 时 commit 带 ignoreUnboundFormulas=true（仅当主线确认预览里的阻断与本 AC 无关时使用）
import fs from 'node:fs';
import path from 'node:path';
import { guardDb, login, api, sql, check, fail, save, outDir, unwrap, S, FAILS } from './lib.mjs';
const O = outDir('out-C5');
const FX = path.join(S, '../../夹具');
const PKG = { 'v1.0': path.join(FX, '施耐德成环检测-导出包-v1.0.json'), 'v1.1': path.join(FX, '施耐德成环检测-导出包-v1.1.json') };
const WANT = { col_1: '[物料.材料成本(小计)]', col_2: '[物料.回收成本(小计)]', col_3: '[产品小计(总计)]' };
const OLD = { col_1: '[物料.材料成本]', col_2: '[物料.回收成本]', col_3: '[产品小计(总计)]' };
const [mode, label] = process.argv.slice(2);
const exprs = (cols) => Object.fromEntries((cols || []).map((c) => [c.col_key, c.expression]));
const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);

function precheckPackages() {
  for (const [v, f] of Object.entries(PKG)) {
    const b = JSON.parse(fs.readFileSync(f, 'utf-8'));
    const ex = b.components.find((c) => c.name === 'ex1');
    const wl = b.components.find((c) => c.name === '物料');
    const subs = (wl?.fields || []).filter((x) => x.is_subtotal === true || x.is_subtotal === 'true').map((x) => x.name);
    check(b.bundleVersion === v.slice(1), `[前置] ${v} 包 bundleVersion=${b.bundleVersion}`);
    check(!!ex && eq(exprs(ex.excelColumns), OLD), `[前置] ${v} 包 ex1 为旧写法 ${JSON.stringify(exprs(ex?.excelColumns))}`);
    check(subs.includes('材料成本') && subs.includes('回收成本'), `[前置] ${v} 包内「物料」材料成本/回收成本勾了小计（小计列=${subs}）`);
    check(ex?.excelColumns?.[0]?.tabs?.[0]?.tabKey === wl?.id, `[前置] ${v} 包 ex1.col_1.tabs[0].tabKey 指向包内物料 id（${wl?.id}）`);
  }
}
async function findDir(name) {
  const r = await api(`/api/cpq/component-directories?keyword=${encodeURIComponent(name)}`);
  const walk = (ns) => ns.flatMap((n) => [n, ...walk(n.children || [])]);
  return walk(unwrap(r) || []).filter((d) => d.name === name);
}
async function mkDir(name) {
  const ex = await findDir(name);
  if (ex.length) throw new Error(`目录 ${name} 已存在 ${ex.length} 个（上一轮残留？）停止，报主线`);
  const r = await api('/api/cpq/component-directories', { method: 'POST', body: JSON.stringify({ name, parentId: null }) });
  const id = unwrap(r)?.id; if (!id) throw new Error(`建目录失败 ${r.status} ${r.text}`);
  console.log(`[造数] 目录 ${name} = ${id}`); return id;
}
const normPreview = (p) => ({ bundleVersion: p?.bundleVersion, checksumValid: p?.checksumValid, canCommit: p?.canCommit, blockers: p?.blockers, warnings: p?.warnings });
async function preview(dirId, text, tag) {
  const r = await api(`/api/cpq/component-directories/${dirId}/import?conflictPolicy=RENAME`, { method: 'POST', body: text });
  save(O, `${tag}-preview.json`, r.json ?? r.text);
  check(r.status === 200, `${tag} 预览 status=${r.status}`);
  const p = unwrap(r); console.log(`[${tag}] preview`, JSON.stringify(normPreview(p)));
  return p;
}
async function importInto(dirId, text, tag) {
  const p = await preview(dirId, text, tag);
  if (!p?.canCommit) fail(`${tag} canCommit=false blockers=${JSON.stringify(p?.blockers)}`);
  const q = `conflictPolicy=RENAME${process.env.IGNORE_UNBOUND === '1' ? '&ignoreUnboundFormulas=true' : ''}`;
  const r = await api(`/api/cpq/component-directories/${dirId}/import/commit?${q}`, { method: 'POST', body: text });
  save(O, `${tag}-commit.json`, r.json ?? r.text);
  const c = unwrap(r);
  check(r.status === 200 && c?.createdCount === 10, `${tag} 提交 status=${r.status} createdCount=${c?.createdCount} msg=${r.json?.message ?? ''}`);
  return { p, c };
}
async function ex1Cols(dirId, tag) {
  const ids = sql(`select id from component where directory_id='${dirId}' and name='ex1'`).split('\n').filter(Boolean);
  check(ids.length === 1, `${tag} 目录内恰 1 个 ex1（实际 ${ids.length}）`);
  if (!ids.length) return {};
  const g = await api(`/api/cpq/components/${ids[0]}`);
  const cols = unwrap(g)?.excelColumns;
  save(O, `${tag}-ex1.json`, g.json ?? g.text);
  const wlId = sql(`select id from component where directory_id='${dirId}' and name='物料'`);
  console.log(`[${tag}] ex1 列 ${JSON.stringify(exprs(cols))}；col_1.tabKey=${cols?.[0]?.tabs?.[0]?.tabKey} 本目录物料=${wlId}`);
  return exprs(cols);
}

guardDb();
await login();
if (mode === 'run') {
  precheckPackages();
  const dirs = { 'v1.0': await mkDir('RP0916C-导入v10'), 'v1.1': await mkDir('RP0916C-导入v11') };
  const ab = {};
  for (const v of ['v1.0', 'v1.1']) {
    const text = fs.readFileSync(PKG[v], 'utf-8');
    const { p } = await importInto(dirs[v], text, `导入${v}`);
    ab[v] = normPreview(p);   // 首次预览（空目录）作为④分支侧原始记录
    const e = await ex1Cols(dirs[v], `导入${v}`);
    check(eq(e, WANT), `AC-16① ${v} 导入后 ex1 三列 = ${JSON.stringify(WANT)}（实际 ${JSON.stringify(e)}）`);
  }
  // ② 导出
  const exported = {};
  for (const v of ['v1.0', 'v1.1']) {
    const r = await api(`/api/cpq/component-directories/${dirs[v]}/export`);
    save(O, `导出-from${v}.json`, r.text);
    const b = r.json;
    check(r.status === 200 && b?.bundleVersion === '1.2', `AC-16② 导出(${v} 目录) bundleVersion=${b?.bundleVersion}`);
    const ex = b?.components?.find((c) => c.name === 'ex1');
    check(eq(exprs(ex?.excelColumns), WANT), `AC-16② 导出包 ex1 三列 ${JSON.stringify(exprs(ex?.excelColumns))}`);
    check((b?.components || []).length === 10, `导出包组件数 = 10（实际 ${(b?.components || []).length}）`);
    exported[v] = r.text;
  }
  // ③ 再导入（用 v1.1 目录导出的 1.2 包）
  const d12 = await mkDir('RP0916C-再导入v12');
  const { p: p12 } = await importInto(d12, exported['v1.1'], '再导入v12');
  check(p12?.bundleVersion === '1.2', `再导入预览 bundleVersion=${p12?.bundleVersion}`);
  const e12 = await ex1Cols(d12, '再导入v12');
  check(eq(e12, WANT), `AC-16③ 再导入后 ex1 三列不变 ${JSON.stringify(e12)}`);
  save(O, '造数清单.json', { dirs: { ...dirs, 'v1.2': d12 }, at: new Date().toISOString() });
  // ④ 分支侧：与 master 侧同一时点口径——在已存在的 v10 目录上再预览两份旧包（只读）
  const abNow = {};
  for (const v of ['v1.0', 'v1.1']) abNow[v] = normPreview(await preview(dirs['v1.0'], fs.readFileSync(PKG[v], 'utf-8'), `AB-branch-${v}`));
  save(O, 'ab-branch.json', { firstPreview: ab, samePointPreview: abNow });
} else if (mode === 'ab') {
  if (!label) throw new Error('需要 label');
  const d = await findDir('RP0916C-导入v10');
  if (d.length !== 1) throw new Error(`找不到唯一的 RP0916C-导入v10（${d.length}）——先跑 run`);
  const abNow = {};
  for (const v of ['v1.0', 'v1.1']) abNow[v] = normPreview(await preview(d[0].id, fs.readFileSync(PKG[v], 'utf-8'), `AB-${label}-${v}`));
  save(O, `ab-${label}.json`, { samePointPreview: abNow });
} else if (mode === 'compare') {
  const rd = (f) => (fs.existsSync(path.join(O, f)) ? JSON.parse(fs.readFileSync(path.join(O, f), 'utf-8')) : null);
  const b = rd('ab-branch.json'), m = rd('ab-master.json');
  if (!b || !m) fail('缺 ab-branch.json 或 ab-master.json');
  else {
    const hint = (x) => (JSON.stringify(x?.warnings || []) + JSON.stringify(x?.blockers || [])).match(/[^"]*旧格式[^"]*/g) || [];
    const rows = [];
    for (const v of ['v1.0', 'v1.1']) {
      const bb = b.samePointPreview[v], mm = m.samePointPreview[v];
      rows.push(`| ${v} | 接口 bundleVersion | ${bb?.bundleVersion} | ${mm?.bundleVersion} | ${bb?.bundleVersion === mm?.bundleVersion ? '一致' : '不一致'} |`);
      rows.push(`| ${v} | 接口 旧格式提示 | ${hint(bb).join('；') || '无'} | ${hint(mm).join('；') || '无'} | ${eq(hint(bb), hint(mm)) ? '一致' : '不一致'} |`);
      rows.push(`| ${v} | 接口 warnings/blockers 全量 | ${JSON.stringify([bb?.warnings, bb?.blockers])} | ${JSON.stringify([mm?.warnings, mm?.blockers])} | ${eq([bb?.warnings, bb?.blockers], [mm?.warnings, mm?.blockers]) ? '一致' : '不一致（逐条归因）'} |`);
      check(eq(hint(bb), hint(mm)) && bb?.bundleVersion === mm?.bundleVersion, `AC-16④ ${v} 接口侧旧格式提示与 master 一致`);
      const ub = rd('ab-ui-branch.json'), um = rd('ab-ui-master.json');
      if (ub && um) {
        rows.push(`| ${v} | 界面 旧格式提示（仅参考，D-7 已确认 master 前端按 ≠1.1 判断） | ${ub[v]} ${ub[v + '_text'] || ''} | ${um[v]} ${um[v + '_text'] || ''} | ${ub[v] === um[v] ? '一致' : '不一致（参考，不计失败）'} |`);
      } else rows.push(`| ${v} | 界面 旧格式提示 | 未跑 | 未跑 | 未验证 |`);
    }
    const md = ['| 包 | 维度 | 本分支 | master | 结论 |', '|---|---|---|---|---|', ...rows].join('\n');
    console.log(md); save(O, 'AC-16④-AB对照.md', md + '\n');
  }
} else { console.log('用法见文件头'); process.exit(2); }
console.log(`RESULT FAILS=${FAILS}`);
process.exit(FAILS ? 1 : 0);
