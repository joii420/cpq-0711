/**
 * task-260909 · S1 只读片 · **A 组：报价侧零回归 + 定位器阳性对照**
 *
 * 覆盖 AC：**AC-21**（报价侧无副作用）、**AC-22**（`usage='QUOTE'` 配置逐字节不变）
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 🎯 **本文件必须最先跑**，因为它同时是两件事：
 *
 *  ① AC-21/AC-22 的验收 —— 报价侧在本次改动前后**逐位相同**；
 *  ② 🚨 **整套定位器栈的阳性对照**（`testing.md §4.4`）——
 *     报价侧在**修复前就应当是绿的**。它红 = 我的量具（卡片/页签/表格选择器）坏了，
 *     **不是产品坏了**。没有这条对照，A 组之外任何一条红都无法归因：
 *     「行数对不上」既可能是产品缺陷，也可能是我压根没点到那个页签。
 *
 * 🚫 写入面：无。本文件只 `goto` + 读 DOM + 只读 SQL。
 * 🚫 不调 refresh-snapshot（那是 S-全局 的写入面）。
 */
import { test, expect } from '@playwright/test';
import { loginAs } from './fixtures/auth';
import {
  ADMIN, QUOTATION_NO, gotoStep2, switchToQuote, cardOf, switchTabInCard,
  readTable, loadingCountIn, shot, shotCard, writeEvidence, appendEvidence,
  sqlRows, recordBackendIdentity, assertServingExpectedDb, loginApi,
} from './fixtures/task260909tree';

/**
 * AC-21 原文的行数基线（`需求文档.md §③` 逐字）：
 *   S0001 = 产品 1 / BOM 4 / 材质元素 2 / 加工费 1 / 自制加工费 1
 *   （改动前基线另记：S0004 = 1/6/4/1/2，S0008 = 1/6/3/1/1，S0012 = 1/5/4/1/1）
 *
 * ⚠️ 这是**针对性断言**不是全局计数：锚定到「这张单、这张卡片、这个页签」，
 *    别的会话在共享库上造数不会影响它（`testing.md §4.5`）。
 */
const QUOTE_TABS = ['产品', 'BOM', '材质元素', '加工费', '自制加工费'] as const;
const QUOTE_BASELINE: Record<string, Record<string, number>> = {
  S0001: { 产品: 1, BOM: 4, 材质元素: 2, 加工费: 1, 自制加工费: 1 },
  S0004: { 产品: 1, BOM: 6, 材质元素: 4, 加工费: 1, 自制加工费: 2 },
  S0008: { 产品: 1, BOM: 6, 材质元素: 3, 加工费: 1, 自制加工费: 1 },
  S0012: { 产品: 1, BOM: 5, 材质元素: 4, 加工费: 1, 自制加工费: 1 },
};

test.describe.configure({ mode: 'serial' });

test('T-A0 环境正身：记录后端进程 + 实测它连的确实是 cpq_db_0724', async () => {
  const id = recordBackendIdentity();
  const cookie = await loginApi(ADMIN.username, ADMIN.password);
  await assertServingExpectedDb(cookie);
  console.log(id);
});

test('T-A1 / AC-22：usage=QUOTE 的生效配置 id + sql_template 逐字节不变', async () => {
  const rows = sqlRows(
    "SELECT id, name, md5(sql_template) AS md5, length(sql_template) AS len, updated_at " +
    "FROM costing_bom_tree_config WHERE usage='QUOTE' AND is_active",
  );

  // 断言前先断言非空（`testing.md §3`：空结果会让下面的循环 0 次、断言空跑）
  expect(rows.length, 'usage=QUOTE 的生效配置应恰好 1 条（唯一索引保证）').toBe(1);

  const got = rows[0];
  console.log('[AC-22] QUOTE active =', JSON.stringify(got));
  appendEvidence('AC-22-quote-config.txt',
    `${new Date().toISOString()} ${JSON.stringify(got)}\n`);

  // 立项期实测基线（2026-09-09 10:xx，本片进场采样）：
  //   id  = d6defaa0-354f-4e92-8e89-4bc8454888c3
  //   md5 = d0e8fb6f750c7af36b5d078a6fac0ec3
  // 🚨 允许 env 覆盖，但**默认值必须是硬编码的**——写成「取当前值再和当前值比」
  //    就是典型的零证据自证（`testing.md §5.5`），永远绿。
  const expectId = process.env.PW_QUOTE_CFG_ID || 'd6defaa0-354f-4e92-8e89-4bc8454888c3';
  const expectMd5 = process.env.PW_QUOTE_CFG_MD5 || 'd0e8fb6f750c7af36b5d078a6fac0ec3';

  expect(got.id, `AC-22：QUOTE 生效配置 id 变了（改动前=${expectId}）`).toBe(expectId);
  expect(got.md5, `AC-22：QUOTE 生效配置 sql_template 变了（改动前 md5=${expectMd5}）`).toBe(expectMd5);
});

test('T-A2 / AC-21：报价侧 S0001 五个页签行数与基线逐位相同（兼定位器阳性对照）', async ({ page }) => {
  await loginAs(page, ADMIN.username, ADMIN.password);
  await gotoStep2(page);
  await switchToQuote(page);
  await shot(page, 'AC-21-quote-step2');

  const report: string[] = [];
  const card = await cardOf(page, 'S0001');

  for (const tab of QUOTE_TABS) {
    await switchTabInCard(card, tab);
    const t = await readTable(card);
    const loading = await loadingCountIn(card);

    console.log(`[AC-21] S0001 / ${tab}: rows=${t.rows.length} headers=${JSON.stringify(t.headers)}`);
    t.rows.slice(0, 8).forEach((r, i) => console.log(`   row[${i}] ${JSON.stringify(r)}`));

    // 防空验证：先证明表真的渲染出来了（表头非空），再比行数
    expect(t.headers.length, `S0001/${tab} 表头为空 ⇒ 表没渲染，判【未验证】`).toBeGreaterThan(0);
    expect(loading, `S0001/${tab} 不应出现「加载中…」（AP-31/38）`).toBe(0);
    expect(
      t.rows.length,
      `AC-21：S0001 / ${tab} 行数应为 ${QUOTE_BASELINE.S0001[tab]}（改动前基线），实得 ${t.rows.length}`,
    ).toBe(QUOTE_BASELINE.S0001[tab]);

    report.push(`S0001 / ${tab}: ${t.rows.length} 行  headers=${JSON.stringify(t.headers)}`);
    await shotCard(card, `AC-21-S0001-${tab}`);
  }

  writeEvidence('AC-21-quote-S0001.txt',
    `${QUOTATION_NO} 报价侧 S0001 页签行数（AC-21）\n${report.join('\n')}\n`);
});

test('T-A3 / AC-21 扩展：S0004 / S0008 / S0012 报价侧行数与改动前基线逐位相同', async ({ page }) => {
  await loginAs(page, ADMIN.username, ADMIN.password);
  await gotoStep2(page);
  await switchToQuote(page);

  const report: string[] = [];
  for (const part of ['S0004', 'S0008', 'S0012']) {
    const card = await cardOf(page, part);
    for (const tab of QUOTE_TABS) {
      await switchTabInCard(card, tab);
      const t = await readTable(card);
      const loading = await loadingCountIn(card);
      console.log(`[AC-21] ${part} / ${tab}: rows=${t.rows.length}`);
      expect(t.headers.length, `${part}/${tab} 表头为空 ⇒ 判【未验证】`).toBeGreaterThan(0);
      expect(loading, `${part}/${tab} 不应出现「加载中…」`).toBe(0);
      expect(
        t.rows.length,
        `AC-21：${part} / ${tab} 行数应为 ${QUOTE_BASELINE[part][tab]}（改动前基线），实得 ${t.rows.length}`,
      ).toBe(QUOTE_BASELINE[part][tab]);
      report.push(`${part} / ${tab}: ${t.rows.length} 行`);
    }
  }
  await shot(page, 'AC-21-quote-all-cards');
  writeEvidence('AC-21-quote-others.txt', report.join('\n') + '\n');
});
