// T-C2（AC-14 f）：前缀草稿模板上 PUT /api/cpq/templates/{id}/excel-view-config
// 运行：cd $WT/cpq-frontend && node "$S/T-C2-AC14f-模板保存校验.mjs"   （需分支后端 8293 在跑）
import { guardDb, login, api, sql, check, fail, save, outDir, unwrap, FAILS } from './lib.mjs';
const O = outDir('out-C2');
const NAME = 'RP0916C-模板校验';
guardDb();
const exists = sql(`select count(*) from template where name='${NAME}'`);
if (exists !== '0') { console.log(`已存在 ${exists} 个同名模板（上一轮残留？）停止，报主线`); process.exit(2); }
const [wlId] = sql("select id from component where code='COMP-0002'").split('\n');       // 物料：行键 销售料号+料号，材料成本勾小计
const [qtId] = sql("select id from component where code='COMP-0006'").split('\n');       // 其他费用：行键 销售料号+类别，费用不勾小计
check(!!wlId && !!qtId, `前置组件存在 物料=${wlId} 其他费用=${qtId}`);
console.log('前置字段:', sql(`select code, row_key_fields, (select string_agg(f->>'name'||'/'||(f->>'is_subtotal'),',') from jsonb_array_elements(fields) f where f->>'name' in ('材料成本','费用')) from component where id in ('${wlId}','${qtId}')`));
const TAB_WL = { alias: '物料', tabKey: wlId, rowKeyFields: ['销售料号', '料号'] };
const TAB_QT = { alias: '其他费用', tabKey: qtId, rowKeyFields: ['销售料号', '类别'] };
const col = (k, expr, tabs) => ({ col_key: k, title: k, hidden: false, source_type: 'TAB_JOIN_FORMULA', expression: expr, tabs });
let tplId = null;
try {
  await login();
  const cr = await api('/api/cpq/templates', { method: 'POST', body: JSON.stringify({ name: NAME, templateKind: 'QUOTATION', description: 'repair-260916 S-C AC-14f 临时模板' }) });
  save(O, '0-create.json', cr);
  tplId = unwrap(cr)?.id;
  check(cr.status === 200 && !!tplId, `建草稿模板 status=${cr.status} id=${tplId} status字段=${unwrap(cr)?.status}`);
  if (!tplId) throw new Error('建模板失败');
  for (const cid of [wlId, qtId]) {
    const r = await api(`/api/cpq/templates/${tplId}/components`, { method: 'POST', body: JSON.stringify({ componentId: cid }) });
    check(r.status === 200, `模板加组件 ${cid} status=${r.status}`);
  }
  const put = async (tag, cols) => {
    const body = JSON.stringify(cols);
    const r = await api(`/api/cpq/templates/${tplId}/excel-view-config`, { method: 'PUT', body });
    const g = await api(`/api/cpq/templates/${tplId}/excel-view-config`);
    save(O, `${tag}.json`, { request: cols, response: { status: r.status, body: r.json ?? r.text }, getAfter: g.json ?? g.text });
    console.log(`[${tag}] status=${r.status} message=${JSON.stringify(r.json?.message)}`);
    return { r, after: unwrap(g) };
  };
  const exprsOf = (cfg) => {
    const arr = typeof cfg === 'string' ? JSON.parse(cfg) : cfg;
    return Array.isArray(arr) ? Object.fromEntries(arr.map((c) => [c.col_key, c.expression])) : { _shape: JSON.stringify(arr).slice(0, 200) };
  };
  const LEGAL = col('col_1', '[物料.材料成本(小计)]', [TAB_WL]);
  // ① 合法 → 200，且确实落库
  const p1 = await put('1-合法', [LEGAL]);
  check(p1.r.status === 200, `AC-14f① [物料.材料成本(小计)] → 200（实际 ${p1.r.status}）`);
  check(exprsOf(p1.after).col_1 === '[物料.材料成本(小计)]', `AC-14f① 保存后读回 col_1=${JSON.stringify(exprsOf(p1.after))}`);
  // ② 缺列名 → 400，逐字文案；且配置未被改写
  const p2 = await put('2-缺列名', [col('col_1', '[物料(小计)]', [TAB_WL])]);
  const WANT = '页签连表公式列 col_1 的「(小计)」要写在列名后面，如 [页签.列(小计)]';
  check(p2.r.status === 400, `AC-14f② [物料(小计)] → 400（实际 ${p2.r.status}）`);
  check(p2.r.json?.message === WANT, `AC-14f② 文案逐字相等\n   期望: ${WANT}\n   实际: ${p2.r.json?.message ?? p2.r.text}`);
  check(exprsOf(p2.after).col_1 === '[物料.材料成本(小计)]', `AC-14f② 被拒后配置仍为①（读回 ${JSON.stringify(exprsOf(p2.after))}）`);
  // 对照（非 AC 断言，证明③有判别力）：两个不带后缀的明细引用跨行键类 → 现行应被拒
  const pc = await put('C-对照-裸明细跨行键', [LEGAL, col('col_2', '[物料.材料成本] + [其他费用.费用]', [TAB_WL, TAB_QT])]);
  if (pc.r.status === 400) console.log(`[对照] 裸明细跨行键类被拒 ✔ message=${pc.r.json?.message}`);
  else fail(`[对照] 裸明细跨行键类未被拒（status=${pc.r.status}）⇒ 下面③的 200 无判别力，报主线`);
  // ③ 带 (小计) 的引用不参与「明细跨行键类」校验 → 200
  const p3 = await put('3-小计加跨行键明细', [LEGAL, col('col_2', '[物料.材料成本(小计)] + [其他费用.费用]', [TAB_WL, TAB_QT])]);
  check(p3.r.status === 200, `AC-14f③ 同 body 加列 [物料.材料成本(小计)] + [其他费用.费用] → 200（实际 ${p3.r.status} ${p3.r.json?.message ?? ''}）`);
  const e3 = exprsOf(p3.after);
  check(e3.col_1 === '[物料.材料成本(小计)]' && e3.col_2 === '[物料.材料成本(小计)] + [其他费用.费用]', `AC-14f③ 读回两列 ${JSON.stringify(e3)}`);
} catch (e) {
  fail('异常: ' + e.message);
} finally {
  if (tplId) {
    const d = await api(`/api/cpq/templates/${tplId}`, { method: 'DELETE' });
    console.log(`[cleanup] DELETE template ${tplId} → ${d.status}; 残留=${sql(`select count(*) from template where name='${NAME}'`)}`);
  }
  console.log(`RESULT FAILS=${FAILS}`);
  process.exit(FAILS ? 1 : 0);
}
