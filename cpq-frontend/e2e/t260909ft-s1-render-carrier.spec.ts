/**
 * task-260909「取数配置器字段类型选择」· S1 · 渲染等价（AC-6 / AC-7 / AC-10）
 * —— **执行轮 2026-09-10 新增**，用于替代 `t260909ft-s1-render.spec.ts` 里搭不起来的自建场景。
 *
 * 为什么另起一份而不是继续修那份：
 *   ① 那份按**已被证伪的 DRAFT 口径**搭场景（DRAFT 核价模板 components_snapshot 恒 NULL，渲染不出来）；
 *   ② 它的「加产品」走抽屉 UI，本轮实测点完 line item 仍为 0（量具问题，非产品缺陷），
 *      短期修不完，而 AC-6 是本任务风险最高的一条，不该被量具卡死。
 *   ③ 主线点名的**现成载体**恰好就是订正后 AC 要的形态：
 *      自建并已发布的 `T260909FT-模板`(PUBLISHED, 7 页签) + 自建报价单 `QT-20260909-0795`（4 个产品）。
 *
 * 🚦 本 spec **只读 + 渲染**：不建对象、不改结构、不动发布态，🚫 不碰 `核价模板1` / `QT-20260909-0661`。
 * 🚫 断言全部来自 `需求文档.md §③ 三/四` 的 AC 原文。
 */
import { test, expect } from '@playwright/test';
import * as FT from './t260909ft.helpers';

const TPL_NAME = 'T260909FT-模板';
const TAB_BASIC = 'T260909FT-BASIC';
const TAB_INPUT = 'T260909FT-INPUT';

let cookie = '';
let backendUp = false;
let carrier: { quotationId: string; quotationNumber: string; partNo: string } | null = null;

test.beforeAll(async () => {
  try {
    const r = await fetch(`${FT.BACKEND_URL}/api/cpq/health`, { signal: AbortSignal.timeout(4000) });
    backendUp = r.ok;
  } catch { backendUp = false; }
  if (backendUp) cookie = await FT.loginApi();
});

test.beforeEach(async ({ page }) => {
  test.skip(!backendUp, '后端未启动（harness 前置）');
  await FT.uiLogin(page);
  if (!carrier) carrier = resolveCarrier();
});

/** 当场从库里解析载体，🚫 不写死 id（共享库会漂移；写死会变成"红得像业务回归"的假红）。 */
function resolveCarrier() {
  const tplId = FT.sqlScalar(
    `SELECT id::text FROM template WHERE name='${TPL_NAME}' AND status='PUBLISHED' AND template_kind='COSTING' LIMIT 1`);
  expect(tplId, `🚨 夹具缺失：找不到 PUBLISHED 的核价模板「${TPL_NAME}」⇒ AC-6/7/10 判【未验证】`)
    .toMatch(/^[0-9a-f-]{36}$/);

  // 前置：两个页签都挂在这张模板上，且底层组件是**同源双组件**（唯一变量 = field_type）
  const tabs = FT.sqlRows(
    `SELECT tc.tab_name, c.code, c.id::text AS cid FROM template_component tc
       JOIN component c ON c.id=tc.component_id
      WHERE tc.template_id='${tplId}' AND tc.tab_name IN ('${TAB_BASIC}','${TAB_INPUT}')`);
  expect(tabs.length, `🚨 夹具缺失：模板「${TPL_NAME}」上应同时挂「${TAB_BASIC}」「${TAB_INPUT}」两个页签，实际 ${JSON.stringify(tabs)}`)
    .toBe(2);
  const cidBasic = tabs.find((t) => t.tab_name === TAB_BASIC)!.cid;
  const cidInput = tabs.find((t) => t.tab_name === TAB_INPUT)!.cid;

  const fB = FT.dbFieldsOf(cidBasic);
  const fI = FT.dbFieldsOf(cidInput);
  const tmplB = FT.sqlScalar(`SELECT md5(sql_template) FROM component_sql_view WHERE component_id='${cidBasic}'`);
  const tmplI = FT.sqlScalar(`SELECT md5(sql_template) FROM component_sql_view WHERE component_id='${cidInput}'`);
  console.log(`[carrier] ${TAB_BASIC} field_type=${JSON.stringify(fB.map((f) => f.field_type))}`);
  console.log(`[carrier] ${TAB_INPUT} field_type=${JSON.stringify(fI.map((f) => f.field_type))}`);

  // 🔑 「唯一变量是 field_type」必须**当场证明**，否则 AC-6 的「值逐字相同」证不出任何东西
  expect(tmplB, `AC-6 前置：两个组件的 sql_template 必须逐字相同（同源），实际 md5 ${tmplB} vs ${tmplI}`).toBe(tmplI);
  expect(fB.map((f) => f.field_name), `AC-6 前置：两个组件的字段名与顺序必须完全一致`).toEqual(fI.map((f) => f.field_name));
  expect(fB.every((f) => f.field_type === 'BASIC_DATA'),
    `AC-6 前置：${TAB_BASIC} 应全为 BASIC_DATA，实际 ${JSON.stringify(fB.map((f) => f.field_type))}`).toBe(true);
  expect(fI.every((f) => String(f.field_type).startsWith('INPUT_')),
    `AC-6 前置：${TAB_INPUT} 应全为 INPUT_*，实际 ${JSON.stringify(fI.map((f) => f.field_type))}`).toBe(true);

  const q = FT.sqlRows(
    `SELECT q.id::text AS id, q.quotation_number AS num,
            (SELECT li.product_part_no_snapshot FROM quotation_line_item li
              WHERE li.quotation_id=q.id ORDER BY li.sort_order LIMIT 1) AS part_no,
            (SELECT count(*) FROM quotation_line_item li WHERE li.quotation_id=q.id) AS items
       FROM quotation q WHERE q.costing_card_template_id='${tplId}'
        AND q.name LIKE 'T260909FT%' ORDER BY q.created_at DESC LIMIT 1`);
  expect(q.length, `🚨 夹具缺失：找不到绑了「${TPL_NAME}」的自建报价单 ⇒ AC-6/7/10 判【未验证】`).toBe(1);
  expect(Number(q[0].items), `🚨 夹具缺失：报价单 ${q[0].num} 没有产品行 ⇒ 页签会是空的，断言全空跑，判【未验证】`)
    .toBeGreaterThan(0);

  const c = { quotationId: q[0].id, quotationNumber: q[0].num, partNo: q[0].part_no };
  console.log(`[carrier] ${JSON.stringify(c)}`);
  FT.writeEvidence('AC-6-载体.json', JSON.stringify({ ...c, tplId, cidBasic, cidInput }, null, 2) + '\n');
  return c;
}

async function readBothTabs(page: any) {
  await FT.gotoStep2(page, carrier!.quotationId);
  await FT.switchToCosting(page);
  const card = await FT.cardOf(page, carrier!.partNo);
  await FT.switchTabInCard(card, TAB_BASIC);
  const basic = await FT.readTab(card);
  await FT.shot(page, 'AC-6-01-BASIC_DATA页签');
  await FT.switchTabInCard(card, TAB_INPUT);
  const input = await FT.readTab(card);
  await FT.shot(page, 'AC-6-02-INPUT页签');
  return { card, basic, input };
}

// ════════════════════════════════════════════════════════════════════
test('AC-6 核心: 同源双组件（唯一变量 field_type）两个页签逐行逐列可见值逐字相同（判据 = 所有非空单元格）',
  async ({ page }) => {
    test.setTimeout(360_000);
    const { basic, input } = await readBothTabs(page);

    console.log(`[AC-6] BASIC 行数=${basic.rows.length} 非空格=${FT.nonEmptyCellCount(basic)}`);
    console.log(`[AC-6] INPUT 行数=${input.rows.length} 非空格=${FT.nonEmptyCellCount(input)}`);

    // 🚨 防空验证：两侧都必须非空，否则「逐字相同」恒真
    expect(basic.rows.length, `AC-6：${TAB_BASIC} 页签 0 行 ⇒ 比对恒真，判【未验证】`).toBeGreaterThan(0);
    expect(input.rows.length, `AC-6：${TAB_INPUT} 页签 0 行 ⇒ 比对恒真，判【未验证】`).toBeGreaterThan(0);
    expect(FT.nonEmptyCellCount(basic),
      `AC-6：${TAB_BASIC} 页签一个非空单元格都没有 ⇒ BASIC_DATA 侧根本没取到值（这正是本条要抓的缺陷形态），` +
      `或比对会在空数据上恒真。rows=${JSON.stringify(basic.rows)}`).toBeGreaterThan(0);
    expect(FT.nonEmptyCellCount(input), `AC-6：${TAB_INPUT} 页签（阳性对照）一个非空单元格都没有 ⇒ 判【未验证】`)
      .toBeGreaterThan(0);

    expect(basic.rows.length, `AC-6：两个页签行数应相同，实际 BASIC=${basic.rows.length} INPUT=${input.rows.length}`)
      .toBe(input.rows.length);

    // 逐格对照（判据已按用户裁决收窄为「所有非空单元格」；null 格子的占位差异单独登记）
    const diffs: string[] = [];
    const nullCells: string[] = [];
    const table: string[] = ['| 行 | 列 | BASIC_DATA | INPUT_* | 判定 |', '|---|---|---|---|---|'];
    for (let r = 0; r < basic.rows.length; r++) {
      const cols = Math.max(basic.rows[r].length, input.rows[r]?.length ?? 0);
      for (let c = 0; c < cols; c++) {
        const b = basic.rows[r][c] ?? '';
        const i = input.rows[r]?.[c] ?? '';
        const header = basic.headers[c] ?? `#${c}`;
        const bothEmptyish = (v: string) => v === '' || v === '—' || v === '-';
        if (bothEmptyish(b) || bothEmptyish(i)) {
          if (b !== i) { nullCells.push(`行${r + 1}/${header}: BASIC="${b}" INPUT="${i}"`); table.push(`| ${r + 1} | ${header} | ${b} | ${i} | 空占位差异(不计入) |`); }
          else table.push(`| ${r + 1} | ${header} | ${b} | ${i} | 两侧同为空 |`);
          continue;
        }
        table.push(`| ${r + 1} | ${header} | ${b} | ${i} | ${b === i ? '相同' : '**不同**'} |`);
        if (b !== i) diffs.push(`行${r + 1}/${header}: BASIC="${b}" ≠ INPUT="${i}"`);
      }
    }
    FT.writeEvidence('AC-6-逐格对照.md',
      [`# AC-6 · 同源双组件逐格对照（${new Date().toISOString()}）`, '',
        `- 报价单 ${carrier!.quotationNumber} / 产品 ${carrier!.partNo}`,
        `- 行数：BASIC=${basic.rows.length} INPUT=${input.rows.length}`,
        `- 非空单元格：BASIC=${FT.nonEmptyCellCount(basic)} INPUT=${FT.nonEmptyCellCount(input)}`,
        `- 非空格不一致数 = ${diffs.length}`,
        `- 空占位差异数（不计入判据，用户裁决）= ${nullCells.length}`, '',
        ...nullCells.map((x) => `  - ${x}`), '', ...table].join('\n') + '\n');
    console.log(`[AC-6] 非空格不一致=${diffs.length}；空占位差异=${nullCells.length}`);

    expect(diffs, `AC-6：两个页签的**非空单元格**应逐字相同，实际不一致 ${diffs.length} 处：\n${diffs.join('\n')}`)
      .toEqual([]);
  });

// ════════════════════════════════════════════════════════════════════
test('AC-7: BASIC_DATA 页签渲染为纯文本、不存在承载值的 <input>；同卡片 INPUT_* 页签仍是 <input>（阳性对照）',
  async ({ page }) => {
    test.setTimeout(360_000);
    const { basic, input } = await readBothTabs(page);
    console.log(`[AC-7] BASIC 承载值控件数=${basic.totalInputs}；INPUT 承载值控件数=${input.totalInputs}`);
    FT.writeEvidence('AC-7-input计数.txt',
      [`报价单 ${carrier!.quotationNumber} / 产品 ${carrier!.partNo}`,
        `${TAB_BASIC}: 行=${basic.rows.length} 承载值控件数=${basic.totalInputs} 逐格=${JSON.stringify(basic.inputCounts)}`,
        `${TAB_INPUT}: 行=${input.rows.length} 承载值控件数=${input.totalInputs} 逐格=${JSON.stringify(input.inputCounts)}`,
      ].join('\n') + '\n');

    expect(input.totalInputs,
      `AC-7 阳性对照：${TAB_INPUT} 页签应仍有 <input>，实际 ${input.totalInputs} 个 ⇒ ` +
      `若为 0，说明整页压根没渲染，那么「BASIC 侧没有 input」这条是恒真的假绿，判【未验证】`).toBeGreaterThan(0);
    expect(basic.totalInputs,
      `AC-7：${TAB_BASIC}（全 BASIC_DATA）页签**不应存在**承载值的 <input>，实际 ${basic.totalInputs} 个。` +
      `逐格计数=${JSON.stringify(basic.inputCounts)}`).toBe(0);
  });

// ════════════════════════════════════════════════════════════════════
test('AC-10 边界·行身份: 料号列配成 BASIC_DATA 后，行数与对照组相同、part_no_field 仍指向该字段名',
  async ({ page }) => {
    test.setTimeout(360_000);
    const tplId = FT.sqlScalar(
      `SELECT id::text FROM template WHERE name='${TPL_NAME}' AND status='PUBLISHED' AND template_kind='COSTING' LIMIT 1`);
    const cidBasic = FT.sqlScalar(
      `SELECT c.id::text FROM template_component tc JOIN component c ON c.id=tc.component_id
        WHERE tc.template_id='${tplId}' AND tc.tab_name='${TAB_BASIC}'`);
    const pn = FT.sqlScalar(`SELECT coalesce(part_no_field,'') FROM component WHERE id='${cidBasic}'`);
    const pnType = FT.dbFieldsOf(cidBasic).find((f) => f.field_name === pn)?.field_type;
    console.log(`[AC-10] ${TAB_BASIC} part_no_field=${JSON.stringify(pn)} 该字段 field_type=${pnType}`);

    // 前置：这条 AC 的场景要成立，料号列本身必须**确实是 BASIC_DATA**，否则什么都没验到
    expect(pn, `AC-10 前置：${TAB_BASIC} 的 part_no_field 不应为空`).not.toBe('');
    expect(pnType,
      `AC-10 前置：料号列「${pn}」必须是 BASIC_DATA 才谈得上「配成 BASIC_DATA 后行身份是否还对」，实际 ${pnType} ⇒ 判【未验证】`)
      .toBe('BASIC_DATA');

    const { basic, input } = await readBothTabs(page);
    FT.writeEvidence('AC-10-行身份.txt',
      [`${TAB_BASIC}.part_no_field = ${pn}（field_type=${pnType}）`,
        `行数：BASIC=${basic.rows.length} / INPUT(对照组)=${input.rows.length}`,
        `BASIC 首行=${JSON.stringify(basic.rows[0])}`].join('\n') + '\n');

    expect(basic.rows.length, `AC-10：BASIC 页签 0 行 ⇒ 「行仍能归属到卡片」证不出来，判【未验证】`).toBeGreaterThan(0);
    expect(basic.rows.length,
      `AC-10：料号列配成 BASIC_DATA 后行数应与对照组相同，实际 BASIC=${basic.rows.length} INPUT=${input.rows.length}`)
      .toBe(input.rows.length);
    expect(pn, `AC-10：part_no_field 应仍指向字段名，实际 ${JSON.stringify(pn)}`).toBeTruthy();
  });
