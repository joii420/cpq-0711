// T-A1 · AC-11（前端部分）· H85「材料成本」用户原场景
// 断言来源：问题说明.md ⑥ AC-11 ①②③④（前端侧）
//   ① 回显文字中该项为 [来料固定加工费.加工费(小计)]
//   ② 改写为 [来料固定加工费.加工费] 后该项存为 cross_tab_ref agg=NONE
//   ③ 前端引擎结果 = 0.212585104（9 位显示口径）
//   ④ 不改写 = 0.463735546
// 数据：证据/离线判决/snap.jsonl（QT-20260916-0881 所用模板 v1.8 冻结快照）+ evalh85.mts 同一组行值。
// 纯离线：不连库、不调接口。
//
// 运行（在 worktree 内）：
//   D=<T>/证据/离线判决; S=<T>/证据/测试/S-A
//   node --import $D/hook.mjs $S/ta1-h85.mts | tee $S/输出/ta1-$(date +%y%m%d-%H%M%S).txt ; echo "exit=${PIPESTATUS[0]}"
// 证伪（阳性对照，期望 ② ③ FAIL、exit=1）：
//   SA_IMPL=master node --import $D/hook.mjs $S/ta1-h85.mts
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import {
  WT, MAIN, SERIALIZE_REL, ENGINE_REL, check, warn, precondition, finish,
  same, firstDiff, walk, countOf, r9, assertMainIsMaster, branchProvenance,
} from './sa-common.mts';

const HERE = fileURLToPath(new URL('.', import.meta.url));
const IMPL = process.env.SA_IMPL ?? 'branch';
const ROOT = IMPL === 'master' ? MAIN : WT;
console.log(`# T-A1 AC-11 前端  impl=${IMPL} root=${ROOT}  at=${new Date().toISOString()}`);
if (IMPL === 'master') { assertMainIsMaster(SERIALIZE_REL); assertMainIsMaster(ENGINE_REL); } else branchProvenance();

const { expressionToTokens, tokensToDrawerExpression } = await import(`${ROOT}/${SERIALIZE_REL}`);
const { evaluateExpression } = await import(`${ROOT}/${ENGINE_REL}`);
precondition(typeof expressionToTokens === 'function' && typeof tokensToDrawerExpression === 'function'
  && typeof evaluateExpression === 'function', 'api.md §5 三个函数均可导入');

// ---------- 夹具 ----------
const snap: any[] = readFileSync(`${HERE}/../../离线判决/snap.jsonl`, 'utf8').trim().split('\n').map((l) => JSON.parse(l));
precondition(snap.length > 0, `snap.jsonl 非空（${snap.length} 行）`);
const ID: Record<string, string> = { // 与 evalh85.mts / parse.mts 同一组真实组件 id
  'COMP-0001': '9612f71d-a5e3-48ce-b1d7-7dfc456125a7', // 产品
  'COMP-0002': 'b3445979-e3d6-4cdb-ab2d-53758b5f2eb2', // 物料（宿主）
  'COMP-0003': '5d5b34f8-a4ae-4704-a91b-aab7d9316e71', // 材料成本
  'COMP-0004': '4db28822-85c6-4522-ac62-61ef2393a99c', // 来料固定加工费
};
const WL = ID['COMP-0002'], JG = ID['COMP-0004'], MC = ID['COMP-0003'];
// tabDefs 按 tab-defs 接口形状（api.md §5）从快照字段构造：
// detailFields = 非文本字段（问题说明 4.2②），allFields = 全部字段，subtotalCols = 勾小计字段，rowKeyFields = 快照行键
function td(code: string, self = false) {
  const d = snap.find((x) => x.code === code);
  precondition(!!d && Array.isArray(d.fields) && d.fields.length > 0, `快照含 ${code} 且字段非空`);
  return {
    alias: code, tabKey: ID[code], componentId: ID[code], componentName: d.tab,
    rowKeyFields: d.rowkey ?? [],
    detailFields: d.fields.filter((f: any) => f.field_type !== 'INPUT_TEXT').map((f: any) => f.name),
    allFields: d.fields.map((f: any) => f.name),
    subtotalCols: d.fields.filter((f: any) => f.is_subtotal === true).map((f: any) => f.name),
    ...(self ? { self: true } : {}),
  };
}
const tabDefs: any[] = [td('COMP-0002', true), td('COMP-0001'), td('COMP-0003'), td('COMP-0004')];
// 抽屉传入的是组件自身行键（主线审核 260917 第 2 点）：取快照里 COMP-0002 的 rowkey（= component.row_key_fields）
const SELF_RK: string[] = snap.find((x) => x.code === 'COMP-0002')?.rowkey ?? [];
precondition(SELF_RK.length > 0, `COMP-0002 自身行键非空：${JSON.stringify(SELF_RK)}`);
console.log('tabDefs =', JSON.stringify(tabDefs));

const wl = snap.find((d) => d.code === 'COMP-0002');
const orig: any[] = wl?.formulas?.find((f: any) => f.name === '非银点类材料成本公式')?.expression;
precondition(Array.isArray(orig) && orig.length > 0, `COMP-0002「非银点类材料成本公式」token 非空（${orig?.length}）`);
const jgSubInOrig = [...walk(orig)].filter(({ token: t }) => t.type === 'component_subtotal' && t.value === '加工费' && t.component_code === 'COMP-0004');
precondition(jgSubInOrig.length === 1, `原公式恰含 1 处 component_subtotal(COMP-0004.加工费)（实际 ${jgSubInOrig.length}）`);

// 行值：与 evalh85.mts 逐字相同（QT-20260916-0881 H85，料号 00144）
const crossTabRows = {
  [MC]: [
    { 销售料号: 'S3110520422', 料号: '00255', '组成含量（%）': '100', 税后单价: '25568.584070796' },
    { 销售料号: 'S3120011203', 料号: '00144', '组成含量（%）': '85', 税后单价: '89.503716814' },
    { 销售料号: 'S3120011203', 料号: '00144', '组成含量（%）': '15', 税后单价: '21.388938053' },
    { 销售料号: 'S3110520422', 料号: '00256', '组成含量（%）': '100', 税后单价: '89.503716814' },
    { 销售料号: 'S3110520422', 料号: '00257', '组成含量（%）': '100', 税后单价: '105' },
  ],
  [JG]: [
    { 销售料号: 'S3120011203', 料号: 'S3110520422', 加工费: '120' },
    { 销售料号: 'S3120011203', 料号: '00144', 加工费: '7.5' },
  ],
};
const subtotals = { 'COMP-0001#税率': '1.13', 'COMP-0004#加工费': '127.5' };
const row = { 销售料号: 'S3120011203', 料号: '00144', 产出类型: '非银点类', 材料毛重: '0.002365', 来料损耗率: '5' };
function evalH85(label: string, toks: any[]) {
  const fv: any = { 材料毛重: '0.002365', 来料损耗率: '5' };
  const diag: any = {};
  const v = evaluateExpression(toks, fv, subtotals, {}, {}, undefined, undefined, undefined, undefined, undefined, row, crossTabRows, diag, undefined, fv, row);
  console.log(`eval ${label}: raw=${JSON.stringify(v)} r9=${v == null ? v : r9(v)} crossTabError=${diag.crossTabError ?? ''}`);
  precondition(v !== undefined && v !== null && String(v) !== '' && !diag.crossTabError, `${label} 求值有结果且无 crossTabError`);
  return r9(v);
}

// ---------- ① 回显 ----------
const SUB = '[来料固定加工费.加工费(小计)]';
const BARE = '[来料固定加工费.加工费]';
const text0: string = tokensToDrawerExpression(orig, tabDefs, WL);
console.log('回显 text0 =', text0);
precondition(typeof text0 === 'string' && text0.length > 0, '回显文字非空');
check('AC-11①', countOf(text0, SUB) === 1, `回显中 ${SUB} 出现次数=${countOf(text0, SUB)}（应 1）`);

// 不改写的往返（④ 的第二份输入 + 信息性比对）
let back0: any[] = [];
try { back0 = expressionToTokens(text0, tabDefs, SELF_RK, WL); } catch (e: any) { check('AC-11④-往返', false, `原样回显再解析抛错：${e?.message}`); }
if (back0.length) {
  const d = firstDiff(orig, back0);
  if (d) warn(`原样往返与快照 token 不逐字段相等（AC-11 未直接断言，AC-13 另测）：${d}`);
  else console.log('INFO 原样往返与快照 token 逐字段相等');
}

// ---------- ② 改写并解析 ----------
const text1 = text0.split(SUB).join(BARE);
console.log('改写 text1 =', text1);
precondition(countOf(text1, BARE) === 1 && countOf(text1, SUB) === 0, '改写后文字恰含 1 处不带后缀的加工费引用');
let tok1: any[] = [];
try { tok1 = expressionToTokens(text1, tabDefs, SELF_RK, WL); } catch (e: any) { check('AC-11②', false, `改写文字解析抛错：${e?.message}`); }
mkdirSync(`${HERE}/输出`, { recursive: true });
writeFileSync(`${HERE}/输出/ta1-rewritten-tokens-${IMPL}.json`, JSON.stringify(tok1, null, 1));
if (tok1.length) {
  const refs = [...walk(tok1)].filter(({ token: t }) =>
    (t.type === 'component_subtotal' && t.value === '加工费') || (t.type === 'cross_tab_ref' && t.target === '加工费'));
  console.log('改写后引用加工费的 token =', JSON.stringify(refs));
  const ok = refs.length === 1 && refs[0].token.type === 'cross_tab_ref' && refs[0].token.agg === 'NONE';
  check('AC-11②', ok, `该项 type=${refs[0]?.token.type} agg=${refs[0]?.token.agg}（应 cross_tab_ref / NONE，且唯一；实际命中 ${refs.length}）`);
  if (ok && refs[0].token.source !== JG) warn(`该项 source=${refs[0].token.source}，不是来料固定加工费 ${JG}`);
  // 信息性：除该项外其余 token 与原公式一致（保证值差异只来自该项）
  if (ok && tok1.length === orig.length) {
    const i = refs[0].path[0] as number;
    const a = orig.filter((_, k) => k !== i), b = tok1.filter((_, k) => k !== i);
    if (!same(a, b)) warn(`除该项外其余 token 与原公式不一致：${firstDiff(a, b)}`);
  } else if (ok) warn(`改写后 token 数 ${tok1.length} ≠ 原 ${orig.length}`);
}

// ---------- ③④ 引擎求值 ----------
const vOrig = evalH85('不改写(快照原 token)', orig);
check('AC-11④', vOrig === '0.463735546', `不改写 = ${vOrig}（应 0.463735546）`);
if (back0.length) {
  const vBack0 = evalH85('不改写(回显→解析往返)', back0);
  check('AC-11④-往返', vBack0 === '0.463735546', `原样往返后 = ${vBack0}（应 0.463735546）`);
}
if (tok1.length) {
  const v1 = evalH85('改写后', tok1);
  check('AC-11③', v1 === '0.212585104', `改写后 = ${v1}（应 0.212585104）`);
}
finish();
