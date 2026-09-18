// T-C5（AC-16，D-12/D-15 修订：导出导入行为不变；另负责 AC-15④ 与 AC-16⑤ 的两栈比对）
//   node T-C5-AC16-导出导入.mjs run        # 本分支后端(8293)：①②③ + ④本分支侧（v1.0 预览/提交被拒文字）→ ab-branch.json
//   node T-C5-AC16-导出导入.mjs ab master  # master 后端(同端口 8293，连同一个一次性库)：④ master 侧 → ab-master.json（v1.0 提交按规则整包回滚，零残留）
//   node T-C5-AC16-导出导入.mjs compare    # ④ 接口 A/B + ⑤ 界面 A/B（ab-ui-*.json，来自 spec）+ AC-15④（out-C4/AC-15④-values-*.json）
import fs from 'node:fs';
import path from 'node:path';
import { guardDb, login, api, sql, check, fail, save, outDir, unwrap, S, FAILS } from './lib.mjs';
const O = outDir('out-C5');
const FX = path.join(S, '../../夹具');
const PKG = { 'v1.0': path.join(FX, '施耐德成环检测-导出包-v1.0.json'), 'v1.1': path.join(FX, '施耐德成环检测-导出包-v1.1.json') };
const OLD = { col_1: '[物料.材料成本]', col_2: '[物料.回收成本]', col_3: '[产品小计(总计)]' };
const [mode, label] = process.argv.slice(2);
// 组件接口的 excelColumns 实测为 JSON 字符串（导出包里为数组），两种都接受
const exprs = (cols) => { const a = typeof cols === 'string' ? JSON.parse(cols) : cols; return Object.fromEntries((a || []).map((c) => [c.col_key, c.expression])); };
const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const normPreview = (p) => ({ bundleVersion: p?.bundleVersion, checksumValid: p?.checksumValid, canCommit: p?.canCommit, blockers: p?.blockers, warnings: p?.warnings });

function precheckPackages() {
  for (const [v, f] of Object.entries(PKG)) {
    const b = JSON.parse(fs.readFileSync(f, 'utf-8'));
    const ex = b.components.find((c) => c.name === 'ex1');
    check(b.bundleVersion === v.slice(1), `[前置] ${v} 包 bundleVersion=${b.bundleVersion}`);
    check(!!ex && eq(exprs(ex.excelColumns), OLD), `[前置] ${v} 包 ex1 三列 ${JSON.stringify(exprs(ex?.excelColumns))}`);
    check(b.components.length === 10, `[前置] ${v} 包组件数 10（实际 ${b.components.length}）`);
  }
}
async function findDir(name) {
  const r = await api(`/api/cpq/component-directories?keyword=${encodeURIComponent(name)}`);
  const walk = (ns) => ns.flatMap((n) => [n, ...walk(n.children || [])]);
  return walk(unwrap(r) || []).filter((d) => d.name === name);
}
async function mkDir(name) {
  const ex = await findDir(name);
  // RESUME=1：上一次 run 中途因脚本错误退出时，复用它已建的同名目录（只允许恰 1 个），并在日志中注明
  if (ex.length === 1 && process.env.RESUME === '1') { console.log(`[造数] 复用已存在目录 ${name} = ${ex[0].id}（RESUME）`); return ex[0].id; }
  if (ex.length) throw new Error(`目录 ${name} 已存在 ${ex.length} 个（上一轮残留？）停止，报主线`);
  const r = await api('/api/cpq/component-directories', { method: 'POST', body: JSON.stringify({ name, parentId: null }) });
  const id = unwrap(r)?.id; if (!id) throw new Error(`建目录失败 ${r.status} ${r.text}`);
  console.log(`[造数] 目录 ${name} = ${id}`); return id;
}
const compCount = (dirId) => Number(sql(`select count(*) from component where directory_id='${dirId}'`));
async function preview(dirId, text, tag) {
  const r = await api(`/api/cpq/component-directories/${dirId}/import?conflictPolicy=RENAME`, { method: 'POST', body: text });
  save(O, `${tag}-preview.json`, r.json ?? r.text);
  console.log(`[${tag}] preview status=${r.status}`, JSON.stringify(normPreview(unwrap(r))));
  return { status: r.status, p: unwrap(r), message: r.json?.message ?? null };
}
async function commit(dirId, text, tag) {
  const r = await api(`/api/cpq/component-directories/${dirId}/import/commit?conflictPolicy=RENAME`, { method: 'POST', body: text });
  save(O, `${tag}-commit.json`, r.json ?? r.text);
  console.log(`[${tag}] commit status=${r.status} code=${r.json?.code} message=${r.json?.message}`);
  return { status: r.status, code: r.json?.code ?? null, message: r.json?.message ?? r.text, data: unwrap(r) };
}
async function ex1Cols(dirId, tag) {
  const ids = sql(`select id from component where directory_id='${dirId}' and name='ex1'`).split('\n').filter(Boolean);
  check(ids.length === 1, `${tag} 目录内恰 1 个 ex1（实际 ${ids.length}）`);
  if (!ids.length) return {};
  const g = await api(`/api/cpq/components/${ids[0]}`);
  save(O, `${tag}-ex1.json`, g.json ?? g.text);
  const cols = exprs(unwrap(g)?.excelColumns);
  console.log(`[${tag}] ex1 三列 ${JSON.stringify(cols)}`);
  return cols;
}
const isOk = (c) => c.status === 200 && (c.code == null || c.code === 200) && c.data?.createdCount === 10;
/** ④ v1.0：预览 + 提交（期望被拒且零残留），两栈同一套动作 */
async function v10Side(dirId, tag) {
  const text = fs.readFileSync(PKG['v1.0'], 'utf-8');
  const before = compCount(dirId);
  const pv = await preview(dirId, text, `${tag}-v1.0`);
  const cm = await commit(dirId, text, `${tag}-v1.0`);
  const after = compCount(dirId);
  check(!isOk(cm), `AC-16④ ${tag} v1.0 提交被拒（status=${cm.status} code=${cm.code}）`);
  check(!!cm.message && cm.message.length > 0, `AC-16④ ${tag} v1.0 被拒有报错文字: ${cm.message}`);
  check(before === after, `AC-16④ ${tag} v1.0 被拒后目录组件数不变（${before}→${after}，整包回滚）`);
  const v11 = await preview(dirId, fs.readFileSync(PKG['v1.1'], 'utf-8'), `${tag}-v1.1`);
  return { at: new Date().toISOString(), v10: { preview: normPreview(pv.p), previewStatus: pv.status, commitStatus: cm.status, commitCode: cm.code, commitMessage: cm.message }, v11: { preview: normPreview(v11.p), previewStatus: v11.status } };
}

if (mode !== 'compare') { guardDb(); await login(); }
if (mode === 'run') {
  precheckPackages();
  const d10 = await mkDir('RP0916C-导入v10');
  const d11 = await mkDir('RP0916C-导入v11');
  // ① v1.1 导入成功、文字原样
  const t11 = fs.readFileSync(PKG['v1.1'], 'utf-8');
  if (process.env.RESUME === '1' && compCount(d11) === 10) {
    const prev = JSON.parse(fs.readFileSync(path.join(O, '导入v1.1-commit.json'), 'utf-8'));
    console.log(`[RESUME] v1.1 已在上一次 run 导入（commit.json: code=${prev.code} createdCount=${prev.data?.createdCount}），不重复导入`);
    check(prev.code === 200 && prev.data?.createdCount === 10, `AC-16① v1.1 导入成功（取上一次 commit 原始响应）createdCount=${prev.data?.createdCount}`);
  } else {
  const pv = await preview(d11, t11, '导入v1.1');
  check(pv.status === 200 && pv.p?.canCommit === true, `导入v1.1 预览 canCommit=${pv.p?.canCommit} blockers=${JSON.stringify(pv.p?.blockers)}`);
  const c11 = await commit(d11, t11, '导入v1.1');
  check(isOk(c11), `AC-16① v1.1 导入成功 createdCount=${c11.data?.createdCount}`);
  }
  const e11 = await ex1Cols(d11, '导入v1.1');
  check(eq(e11, OLD), `AC-16① ex1 三列与包内逐字相同 ${JSON.stringify(e11)}`);
  // ② 导出
  const ex = await api(`/api/cpq/component-directories/${d11}/export`);
  save(O, '导出-fromv1.1.json', ex.text);
  const b = ex.json;
  check(ex.status === 200 && b?.bundleVersion === '1.1', `AC-16② 导出 bundleVersion=${b?.bundleVersion}（应 1.1）`);
  const ex1 = b?.components?.find((c) => c.name === 'ex1');
  check(eq(exprs(ex1?.excelColumns), OLD), `AC-16② 导出包 ex1 三列 ${JSON.stringify(exprs(ex1?.excelColumns))}`);
  check((b?.components || []).length === 10, `导出包组件数 10（实际 ${(b?.components || []).length}）`);
  // ③ 再导入
  const d12 = await mkDir('RP0916C-再导入v12');
  const pv12 = await preview(d12, ex.text, '再导入');
  check(pv12.p?.bundleVersion === '1.1' && pv12.p?.canCommit === true, `再导入预览 bundleVersion=${pv12.p?.bundleVersion} canCommit=${pv12.p?.canCommit}`);
  const c12 = await commit(d12, ex.text, '再导入');
  check(isOk(c12), `再导入成功 createdCount=${c12.data?.createdCount}`);
  const e12 = await ex1Cols(d12, '再导入');
  check(eq(e12, OLD), `AC-16③ 再导入后三列不变 ${JSON.stringify(e12)}`);
  // ④ 本分支侧（在空目录 v10 上）
  save(O, 'ab-branch.json', await v10Side(d10, 'branch'));
  check(compCount(d10) === 0, 'v10 目录保持为空（供 ⑤ 界面预览与 master 侧复用）');
  save(O, '造数清单.json', { dirs: { v10: d10, v11: d11, v12: d12 }, at: new Date().toISOString() });
} else if (mode === 'ab') {
  if (label !== 'master') throw new Error('ab 只接受 master');
  const d = await findDir('RP0916C-导入v10');
  if (d.length !== 1) throw new Error(`找不到唯一的 RP0916C-导入v10（${d.length}）——先跑 run`);
  save(O, 'ab-master.json', await v10Side(d[0].id, 'master'));
} else if (mode === 'compare') {
  const rd = (dir, f) => (fs.existsSync(path.join(S, dir, f)) ? JSON.parse(fs.readFileSync(path.join(S, dir, f), 'utf-8')) : null);
  const rows = [];
  const row = (ac, dim, b, m, ok) => { rows.push(`| ${ac} | ${dim} | ${b} | ${m} | ${ok ? '一致' : '❌ 不一致'} |`); check(ok, `${ac} ${dim} 两栈一致`); };
  const b = rd('out-C5', 'ab-branch.json'), m = rd('out-C5', 'ab-master.json');
  if (!b || !m) fail('缺 out-C5/ab-branch.json 或 ab-master.json');
  else {
    row('AC-16④', 'v1.0 提交 HTTP 状态/code', `${b.v10.commitStatus}/${b.v10.commitCode}`, `${m.v10.commitStatus}/${m.v10.commitCode}`, b.v10.commitStatus === m.v10.commitStatus && b.v10.commitCode === m.v10.commitCode);
    row('AC-16④', 'v1.0 提交报错文字（逐字）', JSON.stringify(b.v10.commitMessage), JSON.stringify(m.v10.commitMessage), b.v10.commitMessage === m.v10.commitMessage && !!b.v10.commitMessage);
    // 报错文字内含导入时自动生成的组件编号（COMP-nnnn，按全局序列分配，两次导入必然不同）；另给一行屏蔽该编号后的比较
    const mask = (t) => String(t ?? '').replace(/COMP-\d+/g, 'COMP-####');
    row('AC-16④', 'v1.0 提交报错文字（屏蔽自动生成的组件编号后逐字）', JSON.stringify(mask(b.v10.commitMessage)), JSON.stringify(mask(m.v10.commitMessage)), mask(b.v10.commitMessage) === mask(m.v10.commitMessage) && !!b.v10.commitMessage);
    row('AC-16④（参考）', 'v1.0 预览 版本/warnings/blockers', JSON.stringify(b.v10.preview), JSON.stringify(m.v10.preview), eq(b.v10.preview, m.v10.preview));
    row('AC-16④（参考）', 'v1.1 预览 版本/warnings/blockers', JSON.stringify(b.v11.preview), JSON.stringify(m.v11.preview), eq(b.v11.preview, m.v11.preview));
  }
  const ub = rd('out-C5', 'ab-ui-branch.json'), um = rd('out-C5', 'ab-ui-master.json');
  if (!ub || !um) fail('缺 out-C5/ab-ui-branch.json 或 ab-ui-master.json（spec 的 AC-16⑤ 需两栈各跑）');
  else for (const v of ['v1.0', 'v1.1']) {
    const f = (x) => `${x[v].hint ? '有' : '无'}${x[v].version ? `(版本 ${x[v].version})` : ''} ${x[v].line || ''}`;
    row('AC-16⑤', `界面预览 ${v} 旧格式提示`, f(ub), f(um), ub[v].hint === um[v].hint && ub[v].version === um[v].version && ub[v].line === um[v].line);
  }
  const vb = rd('out-C4', 'AC-15④-values-branch.json'), vm = rd('out-C4', 'AC-15④-values-master.json');
  if (!vb || !vm) fail('缺 out-C4/AC-15④-values-{branch,master}.json');
  else for (const k of ['col_1', 'col_2', 'col_3']) {
    row('AC-15④', `后端重算 ${k}（原始值逐字）`, JSON.stringify(vb.values[k]), JSON.stringify(vm.values[k]), eq(vb.values[k], vm.values[k]) && vb.values[k] != null);
  }
  const md = ['| AC | 维度 | 本分支 | master | 结论 |', '|---|---|---|---|---|', ...rows].join('\n');
  console.log(md); save(O, 'AB对照-AC15④-AC16④⑤.md', md + '\n');
} else { console.log('用法见文件头'); process.exit(2); }
console.log(`RESULT FAILS=${FAILS}`);
process.exit(FAILS ? 1 : 0);
