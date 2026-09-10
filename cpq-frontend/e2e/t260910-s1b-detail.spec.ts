/**
 * task-260910 · S-只读片（续）· AC-10 / AC-14 第2条 —— **换用有产品分类的单**
 *
 * 🚨 为什么另起一个文件：首轮用 `QT-20260909-0799` 验 AC-14 第2条得到「.qt-template-badge = 0 个」，
 *    但实查该单 `product_category_id IS NULL` ⇒ **样本恒为空**（testing.md §5.5 ③：
 *    「验了一个恒为空的样本」，既不绿也不红，却极容易被读成「徽标被删过头」）。
 *    换成 `QT-20260909-0794`（product_category_id = 默认分类）才是有效判据。
 */
import { test, expect } from '@playwright/test';
import * as H from './t260910.helpers';

const WITH_CAT = 'QT-20260909-0794';

test('T10.1/T10.2/T14.2 · 详情页（有产品分类的单）：客户视角字段 + 产品分类徽标仍在', async ({ page }) => {
  const cat = H.sqlRows(`SELECT q.product_category_id::text pcid, pc.name cat_name
     FROM quotation q LEFT JOIN product_category pc ON pc.id=q.product_category_id
     WHERE q.quotation_number='${WITH_CAT}'`)[0];
  expect(cat?.pcid, `前置未满足：${WITH_CAT} 没有 product_category_id ⇒ 产品分类徽标本就不该出现，这条判据恒为空（testing.md §5.5 ③），判【未验证】`)
    .toMatch(/^[0-9a-f-]{36}$/);
  const expectCards = H.sqlRows(`SELECT li.sort_order,
      (SELECT p.customer_part_name FROM ds_quote_customer_part p WHERE p.customer_no='CUST-0004'
         AND p.customer_product_no = COALESCE(li.customer_part_no, '§none§') LIMIT 1) name_by_cpn
     FROM quotation_line_item li JOIN quotation q ON q.id=li.quotation_id
     WHERE q.quotation_number='${WITH_CAT}' ORDER BY li.sort_order`);
  const named = expectCards.filter((r) => r.name_by_cpn);
  expect(named.length, `前置未满足：${WITH_CAT} 没有能按 customer_part_no 命中客户料号的行 ⇒ 断言会空跑`).toBeGreaterThan(0);

  await H.uiLogin(page);
  const qid = H.quotationIdOf(WITH_CAT);
  await page.goto(`/quotations/${qid}`);
  await page.waitForTimeout(16000);
  const cards = await H.readCards(page);
  expect(cards.length, `AC-10 详情页应有产品卡片，实际 0 张 ⇒ 断言会空跑`).toBeGreaterThan(0);
  console.log('[详情页卡片] ' + JSON.stringify(cards.map((c) => c.leftTexts)));

  // AC-10：后端换源后客户视角字段自动生效
  const allLeft = cards.map((c) => c.leftTexts.join(' | ')).join(' ## ');
  for (const r of named) {
    expect(allLeft, `AC-10 详情页应显示客户料号名称「${r.name_by_cpn}」，实际=${allLeft}`).toContain(r.name_by_cpn);
  }
  expect(cards[0].inlineStyle ?? '', 'AC-10 详情页不得新增内联 border').not.toContain('border');
  expect(cards[0].hasPartInfoBtn, 'AC-10 详情页不得有「料号信息」按钮').toBe(false);

  // AC-14 第2条：产品分类徽标（与被删的模板徽标共用 .qt-template-badge）必须仍在
  const badges = await page.locator('.qt-template-badge').allInnerTexts();
  console.log('[qt-template-badge] ' + JSON.stringify(badges) + ' 期望含分类名 ' + cat.cat_name);
  expect(badges.length, `AC-14 第2条：详情页「产品分类」徽标必须仍在（本单分类=${cat.cat_name}）。实际 0 个 ⇒ .qt-template-badge 被删过头会连坐打掉产品分类徽标`)
    .toBeGreaterThan(0);
  expect(badges.join(' | '), `AC-14 第2条：徽标应显示产品分类「${cat.cat_name}」`).toContain(cat.cat_name);
  for (const b of badges) {
    expect(b.replace(/\s+/g, ''), `AC-14 徽标不得以「模板:」开头，实际「${b}」`).not.toMatch(/^模板[:：]/);
  }
  // AC-14 第1条（详情页侧的反向确认）：整页不得出现「模板: xxx」
  const moban = await page.locator('text=/模板\\s*[:：]/').count();
  expect(moban, `AC-14 详情页不得出现「模板: xxx」文案，实际 ${moban} 处`).toBe(0);

  await H.shot(page, 'ac10-ac14-详情页-有产品分类');
  H.writeEvidence('ac10-ac14-详情页-有分类.txt',
    `单=${WITH_CAT} 分类=${cat.cat_name}(${cat.pcid})\n期望客户料号名=${JSON.stringify(named)}\n实际左侧=${JSON.stringify(cards.map((c) => c.leftTexts), null, 1)}\nqt-template-badge=${JSON.stringify(badges)}\n模板文案计数=${moban}\n`);
});

/* 🚫 原计划的 T14.3「grep quotation.css 确认 .qt-template-badge 规则还在」**已删除**：
 *    那要读 `cpq-frontend/src/**`，超出本片的可读范围（派工 prompt 段 c 明令禁止读实现）。
 *    AC-14 第2条的**可观测后果**（详情页产品分类徽标仍渲染）已由上一条用例覆盖，
 *    删了 CSS 规则必然导致徽标样式失效/结构缺失，会被上一条抓到。 */
