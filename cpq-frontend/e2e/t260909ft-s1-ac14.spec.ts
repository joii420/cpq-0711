/**
 * task-260909 · S1 · AC-14（无副作用 · 报价侧仍可编辑可保存）
 *
 * AC 原文（需求文档 §③ 六）：
 *   「报价侧编辑页仍**可编辑可保存**：用自建的 `QUOTE` 方言组件（`T260909FT-QUOTE`）验证 ——
 *     其 `INPUT_*` 字段仍渲染为 `<input>`，改值失焦后 `quote-card-edit` 正常写入并可读回。」
 *   🚫 不在既有报价单上改数据。
 *
 * 🚦 载体全部自建（`T260909FT-` 前缀）：自建报价模板（挂 COMP-2424 = T260909FT-QUOTE-NEW）
 *    + 自建报价单 + 自建产品行。🚫 不改任何既有模板/既有报价单。
 *    「加产品」抽屉 UI 本轮跑不通（实测点完**一个创建请求都不发**，只有 batch-expand）
 *    ⇒ 产品行改用 INSERT…SELECT 从自建单 QT-20260909-0795 复制**一行**到自建单里。
 */
import { test, expect } from '@playwright/test';
import * as FT from './t260909ft.helpers';

const QUOTE_COMP = 'COMP-2424';        // T260909FT-QUOTE-NEW（本次代码产出的 QUOTE 组件）
const MY_TAB = 'T260909FT-报价输入';
const EDIT_COL = '材料名';             // COMP-2424 的 TEXT 列 → INPUT_TEXT

let cookie = '';
let backendUp = false;

test.beforeAll(async () => {
  try {
    const r = await fetch(`${FT.BACKEND_URL}/api/cpq/health`, { signal: AbortSignal.timeout(4000) });
    backendUp = r.ok;
  } catch { backendUp = false; }
  if (backendUp) cookie = await FT.loginApi();
});

test('AC-14 无副作用: 自建 QUOTE 方言组件的 INPUT_* 字段仍渲染为 <input>，改值失焦后写入并可读回',
  async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    test.setTimeout(600_000);

    // ── ① 前置：源单（自建）与它的报价模板 ────────────────────────────
    const src = FT.sqlRows(
      `SELECT q.id::text AS id, q.quotation_number AS num, q.customer_id::text AS cust,
              q.product_category_id::text AS cat, q.customer_template_id::text AS tpl,
              (SELECT li.product_part_no_snapshot FROM quotation_line_item li
                WHERE li.quotation_id=q.id ORDER BY li.sort_order LIMIT 1) AS part_no
         FROM quotation q WHERE q.name LIKE '${FT.TAG}%' AND q.customer_template_id IS NOT NULL
           AND (SELECT count(*) FROM quotation_line_item li WHERE li.quotation_id=q.id) > 0
         ORDER BY q.created_at DESC LIMIT 1`);
    expect(src.length, 'AC-14 前置：找不到可作模板的自建源单 ⇒ 判【未验证】').toBe(1);
    const S = src[0] as any;
    console.log(`[AC-14] 源单 ${S.num} 客户=${S.cust} 分类=${S.cat} 报价模板=${S.tpl} 产品=${S.part_no}`);

    const qcId = FT.sqlScalar(`SELECT id::text FROM component WHERE code='${QUOTE_COMP}'`);
    expect(qcId, `AC-14 前置：找不到自建 QUOTE 组件 ${QUOTE_COMP}`).toMatch(/^[0-9a-f-]{36}$/);
    const qcFields = FT.dbFieldsOf(qcId);
    console.log(`[AC-14] ${QUOTE_COMP} field_type=${JSON.stringify(qcFields.map((f) => `${f.field_name}=${f.field_type}`))}`);
    expect(qcFields.every((f) => String(f.field_type).startsWith('INPUT_')),
      `AC-14 前置：${QUOTE_COMP} 应全为 INPUT_*（这正是报价侧的现状口径），实际 ${JSON.stringify(qcFields.map((f) => f.field_type))}`)
      .toBe(true);

    // ── ② 自建报价模板（复制源模板的形状 + 追加我的页签）────────────────
    const tplName = FT.ownName('报价模板');
    const out = FT.sqlOwnedWrite(
      `INSERT INTO template (id, template_series_id, name, version, category, description, usage_note,
         product_attributes, subtotal_formula, components_snapshot, status, created_by, published_at,
         created_at, updated_at, excel_view_config, customer_id, category_id, template_kind, formulas,
         is_default, referenced_variables, sql_views_snapshot, template_sql_views_snapshot)
       SELECT gen_random_uuid(), gen_random_uuid(), '${tplName}', 'v1.0', t.category,
         'task-260909 AC-14 自建（复制自源模板形状）', t.usage_note,
         t.product_attributes, t.subtotal_formula, NULL, 'DRAFT', NULL, NULL,
         now(), now(), t.excel_view_config, t.customer_id, t.category_id, 'QUOTATION', t.formulas,
         false, t.referenced_variables, NULL, '{}'::jsonb
       FROM template t WHERE t.id='${S.tpl}'::uuid
       RETURNING id`, `建自建报价模板 ${tplName}`);
    const myTpl = (out.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i) || [])[0] || '';
    if (myTpl) FT.registerTemplate(myTpl);
    expect(myTpl, `建自建报价模板失败，psql=${JSON.stringify(out)}`).toMatch(/^[0-9a-f-]{36}$/);

    FT.sqlOwnedWrite(
      `INSERT INTO template_component (id, template_id, component_id, tab_name, sort_order, preset_rows,
         formula_assignments, data_driver_path_override, fields_override)
       SELECT gen_random_uuid(), '${myTpl}'::uuid, tc.component_id, tc.tab_name, tc.sort_order, tc.preset_rows,
         tc.formula_assignments, tc.data_driver_path_override, tc.fields_override
       FROM template_component tc WHERE tc.template_id='${S.tpl}'::uuid`,
      `复制源模板页签 → ${tplName}`);
    FT.sqlOwnedWrite(
      `INSERT INTO template_component (id, template_id, component_id, tab_name, sort_order, preset_rows,
         formula_assignments, data_driver_path_override, fields_override)
       VALUES (gen_random_uuid(), '${myTpl}'::uuid, '${qcId}'::uuid, '${MY_TAB}', 99, '[]'::jsonb,
         '{}'::jsonb, NULL, NULL)`,
      `追加我的报价页签「${MY_TAB}」→ ${QUOTE_COMP}`, 1);
    const tabs = FT.sqlInt(`SELECT count(*) FROM template_component WHERE template_id='${myTpl}'`, 'AC-14 页签数');
    expect(tabs, 'AC-14 前置：自建报价模板页签数应 > 1').toBeGreaterThan(1);
    await FT.publishOwnTemplate(cookie, myTpl);

    // ── ③ 自建报价单 + 从源单复制**一行**产品行 ───────────────────────
    const fixture: FT.RenderFixture = {
      customerId: S.cust, customerCode: '', categoryId: S.cat,
      quoteTemplateId: myTpl, salesPartNo: S.part_no, productionNo: '', costBasicRows: 0,
    };
    const { id: qid, number } = await FT.createQuotation(cookie, fixture, 'AC14');
    FT.sqlOwnedWrite(
      `INSERT INTO quotation_line_item
         (id, quotation_id, product_id, template_id, product_attribute_values, subtotal, sort_order,
          created_at, customer_part_no, product_name_snapshot, product_part_no_snapshot,
          part_version_locked, annual_volume, composite_type, row_version)
       SELECT gen_random_uuid(), '${qid}'::uuid, li.product_id, '${myTpl}'::uuid, li.product_attribute_values,
              li.subtotal, 0, now(), li.customer_part_no, li.product_name_snapshot,
              li.product_part_no_snapshot, li.part_version_locked, li.annual_volume,
              coalesce(li.composite_type,'SIMPLE'), 0
         FROM quotation_line_item li
        WHERE li.quotation_id='${S.id}'::uuid AND li.product_part_no_snapshot='${S.part_no}'`,
      `复制 1 行产品行 → 自建单 ${number}`, 1);
    const liCount = FT.sqlInt(`SELECT count(*) FROM quotation_line_item WHERE quotation_id='${qid}'`, 'AC-14 产品行');
    expect(liCount, `AC-14 前置：自建单 ${number} 产品行为 0 ⇒ 渲染是空的，判【未验证】`).toBe(1);

    for (const p of [`/api/cpq/configure-product/quotations/${qid}/refresh-snapshot`,
                     `/api/cpq/quotations/${qid}/ensure-card-values`]) {
      const r = await FT.api(cookie, p, { method: 'POST' });
      console.log(`[AC-14 refresh] POST ${p} → ${r.status}`);
    }

    // ── ④ 渲染报价侧，断言 <input> 存在 ──────────────────────────────
    const writes: string[] = [];
    page.on('response', async (r) => {
      const m = r.request().method();
      if (['POST', 'PUT', 'PATCH'].includes(m) && /\/api\/cpq\//.test(r.url()) && !/batch-expand|login/.test(r.url())) {
        writes.push(`${m} ${r.url().replace(/^https?:\/\/[^/]+/, '')} → ${r.status()}`);
      }
    });
    await FT.uiLogin(page);
    await FT.gotoStep2(page, qid);            // 报价侧是默认视图，🚫 不切核价
    const card = await FT.cardOf(page, S.part_no);
    await FT.switchTabInCard(card, MY_TAB);
    const snap = await FT.readTab(card);
    await FT.shot(page, 'AC-14-01-报价侧自建页签');
    console.log(`[AC-14] 页签 ${MY_TAB} 行数=${snap.rows.length} 承载值控件数=${snap.totalInputs} 表头=${JSON.stringify(snap.headers)}`);

    expect(snap.rows.length, `AC-14：报价侧自建页签 0 行 ⇒ 后面的编辑断言全空跑，判【未验证】`).toBeGreaterThan(0);
    expect(snap.totalInputs,
      `AC-14：${QUOTE_COMP} 的字段全是 INPUT_*，报价侧应渲染为 <input>，实际承载值控件 ${snap.totalInputs} 个。` +
      `逐格=${JSON.stringify(snap.inputCounts)}`).toBeGreaterThan(0);

    // ── ⑤ 改值 → 失焦 → 断言写入 → 刷新读回 ──────────────────────────
    const ci = snap.headers.findIndex((h) => h === EDIT_COL || h.includes(EDIT_COL));
    expect(ci, `AC-14：表头里找不到「${EDIT_COL}」，实际 ${JSON.stringify(snap.headers)}`).toBeGreaterThanOrEqual(0);
    const cell = card.locator('table').first().locator('tbody tr').first().locator('td').nth(ci);
    const input = cell.locator('input').first();
    await expect(input, `AC-14：第 1 行「${EDIT_COL}」格里应有 <input>`).toBeVisible({ timeout: 15_000 });
    const before = await input.inputValue();
    const marker = `T260909FT-${Date.now().toString(36).slice(-6)}`;
    writes.length = 0;
    await input.fill(marker);
    await input.blur();
    await page.waitForTimeout(5000);
    await FT.shot(page, 'AC-14-02-改值失焦后');
    console.log(`[AC-14] 改值 "${before}" → "${marker}"；失焦后写请求=${JSON.stringify(writes)}`);

    expect(writes.length,
      `AC-14：改值失焦后**没有观测到任何写请求**（quote-card-edit 未触发）⇒ 报价侧编辑写不进去。` +
      `观测窗口 5s，捕获=${JSON.stringify(writes)}`).toBeGreaterThan(0);
    expect(writes.some((w) => / → 2\d\d$/.test(w)),
      `AC-14：失焦后的写请求应有 2xx，实际 ${JSON.stringify(writes)}`).toBe(true);

    // 读回：重新进页面，值应还是 marker
    await page.reload();
    await page.waitForLoadState('networkidle');
    await FT.gotoStep2(page, qid);
    const card2 = await FT.cardOf(page, S.part_no);
    await FT.switchTabInCard(card2, MY_TAB);
    const snap2 = await FT.readTab(card2);
    const readBack = snap2.rows[0]?.[ci] ?? '';
    await FT.shot(page, 'AC-14-03-刷新后读回');
    console.log(`[AC-14] 刷新后读回 = "${readBack}"（期望含 "${marker}"）`);

    FT.writeEvidence('AC-14-报价侧可编辑可保存.md',
      [`# AC-14 · 报价侧仍可编辑可保存（${new Date().toISOString()}）`, '',
        `- 自建报价模板 ${tplName} = ${myTpl}（PUBLISHED，${tabs} 页签，含我的页签「${MY_TAB}」→ ${QUOTE_COMP}）`,
        `- 自建报价单 ${number} = ${qid}（1 行产品，复制自自建源单 ${S.num}）`,
        `- ${QUOTE_COMP} field_type = ${JSON.stringify(qcFields.map((f) => `${f.field_name}=${f.field_type}`))}`, '',
        `## 渲染`, `- 行数 = ${snap.rows.length}`, `- 承载值控件数 = ${snap.totalInputs}`,
        `- 逐格控件数 = ${JSON.stringify(snap.inputCounts)}`, '',
        `## 编辑`, `- 改值：「${before}」→「${marker}」（列：${EDIT_COL}）`,
        `- 失焦后写请求 = ${JSON.stringify(writes, null, 2)}`,
        `- 刷新后读回 = 「${readBack}」`].join('\n') + '\n');

    expect(readBack,
      `AC-14：刷新后应读回刚才改的值「${marker}」，实际「${readBack}」⇒ 改了存不住（这正是核价侧的老毛病，` +
      `报价侧不应出现）`).toContain(marker);
  });
