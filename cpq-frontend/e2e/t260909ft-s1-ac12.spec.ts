/**
 * task-260909 · S1 · AC-12（序列 · 渲染）
 *
 * AC 原文（需求文档 §③ 五）：
 *   「在 `T260909FT-BASIC` 上：把某列由 BASIC_DATA 改回 INPUT_TEXT → 保存 → 重新渲染 →
 *     该列仍显示相同的值、但变回 <input>；再改回 BASIC_DATA → 保存 → 渲染 → 变回纯文本且值不变。」
 *
 * 🚦 主线 2026-09-10 裁决：允许在**自建**的 `T260909FT-*` 模板上重新发布；
 *    🚫 任何存量模板一个字节都不许碰。本 spec 只动 `COMP-2422`(T260909FT-BASIC)
 *    与自建模板 `T260909FT-模板` / 自建报价单 `QT-20260909-0795`（`T260909FT-` 前缀）。
 *
 * 🔒 末态必须回到初态：`finally` 里无条件还原 COMP-2422 的 fieldType，并与进场存档逐字比对。
 */
import { test, expect, Page } from '@playwright/test';
import * as FT from './t260909ft.helpers';

const TPL_NAME = 'T260909FT-模板';
const TAB_BASIC = 'T260909FT-BASIC';
const TARGET_COL = '材料名';           // AC-6 实测该列有非空值（"991"），换类型后值应不变

let cookie = '';
let backendUp = false;

test.beforeAll(async () => {
  try {
    const r = await fetch(`${FT.BACKEND_URL}/api/cpq/health`, { signal: AbortSignal.timeout(4000) });
    backendUp = r.ok;
  } catch { backendUp = false; }
  if (backendUp) cookie = await FT.loginApi();
});

/** 只对**自建前缀**对象放行的快照刷新（不改 helpers 里的登记簿守卫）。 */
async function refreshOwnedQuotation(quotationId: string) {
  const nm = FT.sqlScalar(`SELECT coalesce(name,'') FROM quotation WHERE id='${quotationId}'`);
  expect(nm.startsWith(FT.TAG), `🚨 拒绝刷新非自建报价单「${nm}」`).toBe(true);
  for (const p of [`/api/cpq/configure-product/quotations/${quotationId}/refresh-snapshot`,
                   `/api/cpq/quotations/${quotationId}/ensure-card-values`]) {
    const r = await FT.api(cookie, p, { method: 'POST' });
    console.log(`[refresh] POST ${p} → ${r.status}`);
  }
}

/** 把 COMP-2422 的某一列 fieldType 改成 target，走真实 PUT /builder（读回当前配置再改一处）。 */
async function setColumnFieldType(componentId: string, colName: string, target: FT.FieldType) {
  const g = await FT.api(cookie, `/api/cpq/components/${componentId}/builder`);
  expect(g.status, `AC-12：GET /builder 应 2xx，实际 ${g.status}`).toBeLessThan(300);
  const bc = (g.json?.data?.builderConfig ?? g.json?.builderConfig);
  expect(bc?.columns?.length, 'AC-12：GET 回来的 columns 为空 ⇒ 后面都是空跑').toBeGreaterThan(0);
  const hit = bc.columns.find((c: any) => c.fieldName === colName);
  expect(hit, `AC-12：配置里找不到列「${colName}」，实际 ${JSON.stringify(bc.columns.map((c: any) => c.fieldName))}`)
    .toBeTruthy();
  const before = hit.fieldType;
  const body = {
    tabType: bc.tabType, variantKey: bc.variantKey ?? '', dialect: bc.dialect,
    axisScope: bc.axisScope, switches: bc.switches, priceStrategy: bc.priceStrategy,
    columns: bc.columns.map((c: any) => ({
      sourceNodeKey: c.sourceNodeKey, sourceColumn: c.sourceColumn, fieldName: c.fieldName,
      dataType: c.resolvedDataType ?? c.dataType, roles: c.resolvedRoles ?? c.roles ?? [],
      viewColumn: c.viewColumn, isAmount: c.isAmount, inSubtotal: c.inSubtotal,
      fieldType: c.fieldName === colName ? target : c.fieldType,
    })),
  } as any;
  const r = await FT.api(cookie, `/api/cpq/components/${componentId}/builder`, { method: 'PUT', body });
  expect(r.status, `AC-12：保存「${colName}」→ ${target} 应 2xx，实际 ${r.status}：${r.text.slice(0, 400)}`)
    .toBeLessThan(300);
  const landed = FT.dbFieldsOf(componentId).find((f) => f.field_name === colName)?.field_type;
  expect(landed, `AC-12：落库应为 ${target}，实际 ${landed}（改动前 ${before}）⇒ 保存没生效，后面断言无意义`).toBe(target);
  console.log(`[AC-12] ${colName}: ${before} → ${landed}（落库已确认）`);
  return before;
}

async function renderTab(page: Page, quotationId: string, partNo: string) {
  await FT.gotoStep2(page, quotationId);
  await FT.switchToCosting(page);
  const card = await FT.cardOf(page, partNo);
  await FT.switchTabInCard(card, TAB_BASIC);
  return FT.readTab(card);
}

function colIndexOf(snap: any, name: string): number {
  const i = snap.headers.findIndex((h: string) => h === name || h.includes(name));
  expect(i, `AC-12：表头里找不到列「${name}」，实际表头=${JSON.stringify(snap.headers)}`).toBeGreaterThanOrEqual(0);
  return i;
}
const colValues = (snap: any, i: number) => snap.rows.map((r: string[]) => r[i] ?? '');
const colInputs = (snap: any, i: number) => snap.inputCounts.map((r: number[]) => r[i] ?? 0);

test('AC-12 序列: BASIC_DATA → 改 INPUT_TEXT → 渲染（值同、变 <input>）→ 改回 BASIC_DATA → 渲染（值同、变纯文本）',
  async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    test.setTimeout(600_000);
    await FT.uiLogin(page);

    const tplId = FT.sqlScalar(
      `SELECT id::text FROM template WHERE name='${TPL_NAME}' AND status='PUBLISHED' AND template_kind='COSTING' LIMIT 1`);
    const cid = FT.sqlScalar(
      `SELECT c.id::text FROM template_component tc JOIN component c ON c.id=tc.component_id
        WHERE tc.template_id='${tplId}' AND tc.tab_name='${TAB_BASIC}'`);
    const q = FT.sqlRows(
      `SELECT q.id::text AS id, q.quotation_number AS num,
              (SELECT li.product_part_no_snapshot FROM quotation_line_item li
                WHERE li.quotation_id=q.id ORDER BY li.sort_order LIMIT 1) AS part_no
         FROM quotation q WHERE q.costing_card_template_id='${tplId}' AND q.name LIKE '${FT.TAG}%'
         ORDER BY q.created_at DESC LIMIT 1`);
    expect(q.length, 'AC-12 前置：找不到自建载体报价单 ⇒ 判【未验证】').toBe(1);
    const { id: qid, num, part_no: partNo } = q[0] as any;
    const fingerprintBefore = FT.fieldsFingerprint(cid);
    console.log(`[AC-12] 载体 ${num} / 产品 ${partNo} / 组件 ${cid} / 进场指纹 ${fingerprintBefore}`);

    const report: string[] = [`# AC-12 · 序列（${new Date().toISOString()}）`, '',
      `- 载体：${num} / 产品 ${partNo} / 模板 ${TPL_NAME}(PUBLISHED) / 组件 ${TAB_BASIC}=${cid}`,
      `- 目标列：${TARGET_COL}`, `- 进场 fields 指纹：${fingerprintBefore}`, ''];

    let restored = false;
    try {
      // ── ① 初态：BASIC_DATA ────────────────────────────────────────
      const s0 = await renderTab(page, qid, partNo);
      const ci = colIndexOf(s0, TARGET_COL);
      const v0 = colValues(s0, ci); const in0 = colInputs(s0, ci);
      await FT.shot(page, 'AC-12-01-初态BASIC_DATA');
      console.log(`[AC-12] ①初态 ${TARGET_COL} 值=${JSON.stringify(v0)} 该列input数=${JSON.stringify(in0)}`);
      expect(v0.filter((x: string) => x !== '' && x !== '—').length,
        `AC-12 前置：初态「${TARGET_COL}」列一个非空值都没有 ⇒ 「值不变」会在空数据上恒真，判【未验证】。值=${JSON.stringify(v0)}`)
        .toBeGreaterThan(0);
      expect(in0.reduce((a: number, b: number) => a + b, 0),
        `AC-12 前置：初态该列是 BASIC_DATA，不应有 <input>，实际 ${JSON.stringify(in0)}`).toBe(0);
      report.push(`## ① 初态 BASIC_DATA`, `- 值 = ${JSON.stringify(v0)}`, `- 该列 input 数 = ${JSON.stringify(in0)}`, '');

      // ── ② 改 INPUT_TEXT → 保存 → 刷新 → 渲染 ──────────────────────
      await setColumnFieldType(cid, TARGET_COL, 'INPUT_TEXT');
      const snapAfterSave = FT.sqlScalar(
        `SELECT coalesce((SELECT x->>'field_type' FROM template t,
            LATERAL jsonb_array_elements(t.components_snapshot::jsonb) cs,
            LATERAL jsonb_array_elements(cs->'fields') x
           WHERE t.id='${tplId}' AND cs->>'id'=(SELECT id::text FROM component WHERE id='${cid}')
             AND x->>'name'='${TARGET_COL}' LIMIT 1),'<未找到>')`);
      console.log(`[AC-12] 保存后 PUBLISHED 模板 snapshot 里「${TARGET_COL}」的 field_type = ${snapAfterSave}`);
      report.push(`## ② 改 INPUT_TEXT`, `- 保存后模板 snapshot 里该字段 = ${snapAfterSave}`);
      await refreshOwnedQuotation(qid);

      const s1 = await renderTab(page, qid, partNo);
      const ci1 = colIndexOf(s1, TARGET_COL);
      const v1 = colValues(s1, ci1); const in1 = colInputs(s1, ci1);
      await FT.shot(page, 'AC-12-02-改成INPUT_TEXT');
      console.log(`[AC-12] ②改后 ${TARGET_COL} 值=${JSON.stringify(v1)} 该列input数=${JSON.stringify(in1)}`);
      report.push(`- 值 = ${JSON.stringify(v1)}`, `- 该列 input 数 = ${JSON.stringify(in1)}`, '');

      expect(v1, `AC-12②：改成 INPUT_TEXT 后该列**值应不变**。前=${JSON.stringify(v0)} 后=${JSON.stringify(v1)}`)
        .toEqual(v0);
      expect(in1.reduce((a: number, b: number) => a + b, 0),
        `AC-12②：改成 INPUT_TEXT 后该列应**变回 <input>**，实际逐行 input 数 ${JSON.stringify(in1)} 全为 0。\n` +
        `  （模板 snapshot 里该字段此刻是 ${snapAfterSave}）`).toBeGreaterThan(0);

      // ── ③ 改回 BASIC_DATA → 保存 → 刷新 → 渲染 ────────────────────
      await setColumnFieldType(cid, TARGET_COL, 'BASIC_DATA');
      restored = true;
      await refreshOwnedQuotation(qid);
      const s2 = await renderTab(page, qid, partNo);
      const ci2 = colIndexOf(s2, TARGET_COL);
      const v2 = colValues(s2, ci2); const in2 = colInputs(s2, ci2);
      await FT.shot(page, 'AC-12-03-改回BASIC_DATA');
      console.log(`[AC-12] ③改回 ${TARGET_COL} 值=${JSON.stringify(v2)} 该列input数=${JSON.stringify(in2)}`);
      report.push(`## ③ 改回 BASIC_DATA`, `- 值 = ${JSON.stringify(v2)}`, `- 该列 input 数 = ${JSON.stringify(in2)}`, '');

      expect(v2, `AC-12③：改回 BASIC_DATA 后该列**值应不变**。初=${JSON.stringify(v0)} 末=${JSON.stringify(v2)}`)
        .toEqual(v0);
      expect(in2.reduce((a: number, b: number) => a + b, 0),
        `AC-12③：改回 BASIC_DATA 后该列应**变回纯文本**（无 <input>），实际 ${JSON.stringify(in2)}`).toBe(0);
    } finally {
      // 🔒 无条件还原
      if (!restored) {
        try { await setColumnFieldType(cid, TARGET_COL, 'BASIC_DATA'); } catch (e) { console.log('[AC-12] 还原失败: ' + e); }
      }
      const fingerprintAfter = FT.fieldsFingerprint(cid);
      report.push('', `## 收尾还原`, `- 进场指纹 = ${fingerprintBefore}`, `- 收尾指纹 = ${fingerprintAfter}`,
        `- 还原${fingerprintBefore === fingerprintAfter ? '**成功**（逐字回到初态）' : '🚨 **失败** —— 请主线用 证据/AC-12-COMP-2422-fields-BEFORE.json 还原'}`);
      FT.writeEvidence('AC-12-序列.md', report.join('\n') + '\n');
      console.log(`[AC-12] 收尾指纹 ${fingerprintAfter}（进场 ${fingerprintBefore}）`);
      expect(fingerprintAfter,
        `🚨 AC-12 收尾：COMP-2422 未逐字回到初态 ⇒ **停下报主线**，用 证据/AC-12-COMP-2422-fields-BEFORE.json 还原`)
        .toBe(fingerprintBefore);
    }
  });
