/**
 * task-260916 · S-UI · 库层只读用例
 *   T-UI-01 = AC-1 · T-UI-02 = AC-2 · T-UI-04 = AC-5 · T-UI-05 = AC-6
 * SQL 原样取自 需求文档.md §③；before 阶段即 RX-2（阳性对照），after 阶段断言改后值。
 * 零写入：只跑 SELECT（会话强制只读）；不启浏览器。
 */
import { test, expect } from '@playwright/test';
import * as H from './task260916-sui.helpers';

const AC1_SQL = `SELECT element_code, unit_price FROM f_customer_element_price('CUST-0004', DATE '2026-09-16')`;
const AC2_SQL = `SELECT unit_price FROM f_customer_element_price('CUST-0001', DATE '2026-09-16') WHERE element_code = 'Cu'`;
const AC5_SQL = `SELECT material_no, unit_price FROM f_material_element_price('CUST-0001', DATE '2026-09-16', NULL) WHERE element_code = 'Cu' AND material_no IN ('S-3120014539', 'VS-FG01')`;
const AC6_SQL = `SELECT r.material_no, r.element_code, r.element_price FROM ds_quote_element_bom_record r JOIN public.quotation q ON q.id = r.quotation_id WHERE q.quotation_number = 'QT-20260916-0878' AND r.material_no = 'S3120011203' AND r.element_code IN ('Cu','Zn') ORDER BY r.element_code`;

/** 期望值：before = 需求文档记载的改前值；after = AC 断言值。 */
const EXP = {
  ac1: { before: { Cu: '101.1392', Zn: '24.1695', Ag: '28892.5', Ni: '105' }, after: { Cu: '101.13921', Zn: '24.16947', Ag: '28892.5', Ni: '105' } },
  ac2: { before: '171.3671', after: '171.367052' },
  ac5: { before: { 'S-3120014539': '171.368', 'VS-FG01': '171.3671' }, after: { 'S-3120014539': '171.368', 'VS-FG01': '171.367052' } },
  ac6: { Cu: '101.1392', Zn: '24.1695' }, // 两阶段相同（存量不回算）
};

// D-10：V445 已先行应用到开发库，SQL 改前值无法再复现 ⇒ spec-1 不跑 before，改前值以 证据/S-UI/RX-2-改前.txt 为准。
test.skip(H.PHASE !== 'after', 'spec-1 只在 after 阶段跑（改前值见 RX-2-改前.txt）');
test.describe.configure({ mode: 'serial' });

test.beforeAll(() => { H.ensureDirs(); });

test('T-UI-01 · AC-1 · 正泰取价保留 9 位（Cu/Zn/Ag/Ni）', async ({}, ti) => {
  const rows = H.sql<{ element_code: string; unit_price: string }>(
    `SELECT element_code, unit_price::text AS unit_price FROM (${AC1_SQL}) t`);
  H.writeEvid('AC-1.txt', `-- ${H.PHASE} ${new Date().toISOString()}\n${AC1_SQL}\n${H.sqlText(AC1_SQL)}`);
  H.info(ti, 'AC-1 实际值', rows);
  expect(rows.length, 'AC-1 结果非空').toBeGreaterThan(0);
  const exp = EXP.ac1[H.PHASE];
  for (const [code, v] of Object.entries(exp)) {
    const hit = rows.filter(r => r.element_code === code);
    expect(hit.length, `${code} 应恰 1 行`).toBe(1);
    expect(H.decEq(hit[0].unit_price, v), `${code} unit_price 实际=${hit[0].unit_price} 期望=${v}`).toBe(true);
  }
});

test('T-UI-02 · AC-2 · 罗克韦尔 Cu 例外 ×1.2 +50 保留 9 位', async ({}, ti) => {
  const rows = H.sql<{ unit_price: string }>(`SELECT unit_price::text AS unit_price FROM (${AC2_SQL}) t`);
  H.writeEvid('AC-2.txt', `-- ${H.PHASE} ${new Date().toISOString()}\n${AC2_SQL}\n${H.sqlText(AC2_SQL)}`);
  H.info(ti, 'AC-2 实际值', rows);
  expect(rows.length, 'AC-2 应恰 1 行').toBe(1);
  expect(H.decEq(rows[0].unit_price, EXP.ac2[H.PHASE]), `实际=${rows[0].unit_price} 期望=${EXP.ac2[H.PHASE]}`).toBe(true);
});

test('T-UI-04 · AC-5 · 有冻结版本的料号仍取冻结价；无指针料号取 9 位实时价', async ({}, ti) => {
  const rows = H.sql<{ material_no: string; unit_price: string }>(
    `SELECT material_no, unit_price::text AS unit_price FROM (${AC5_SQL}) t`);
  H.writeEvid('AC-5.txt', `-- ${H.PHASE} ${new Date().toISOString()}\n${AC5_SQL}\n${H.sqlText(AC5_SQL)}`);
  H.info(ti, 'AC-5 实际值', rows);
  const exp = EXP.ac5[H.PHASE];
  for (const [mn, v] of Object.entries(exp)) {
    const hit = rows.filter(r => r.material_no === mn);
    expect(hit.length, `${mn} 应恰 1 行（实际行=${JSON.stringify(rows)}）`).toBe(1);
    expect(H.decEq(hit[0].unit_price, v), `${mn} 实际=${hit[0].unit_price} 期望=${v}`).toBe(true);
  }
});

test('T-UI-05 · AC-6 · 存量草稿 QT-20260916-0878 元素快照不回算', async ({}, ti) => {
  const rows = H.sql<{ element_code: string; element_price: string }>(
    `SELECT element_code, element_price::text AS element_price FROM (${AC6_SQL}) t`);
  H.writeEvid('AC-6.txt', `-- ${H.PHASE} ${new Date().toISOString()}\n${AC6_SQL}\n${H.sqlText(AC6_SQL)}`);
  H.info(ti, 'AC-6 实际值', rows);
  expect(rows.length, 'AC-6 应有 Cu、Zn 两行').toBe(2);
  for (const [code, v] of Object.entries(EXP.ac6)) {
    const hit = rows.find(r => r.element_code === code);
    expect(hit, `${code} 行缺失`).toBeTruthy();
    expect(H.decEq(hit!.element_price, v), `${code} 实际=${hit!.element_price} 期望=${v}`).toBe(true);
  }
  // 「与合并前逐值相同」：合并前值 = RX-2-改前.txt 的 AC-6 基线（Cu 101.139200000000 / Zn 24.169500000000），即上面 EXP.ac6
});
