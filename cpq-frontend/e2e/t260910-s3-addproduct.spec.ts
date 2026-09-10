/**
 * task-260910 · **AC-15**（2026-09-10 新增）+ **AC-14 第1条（重定范围）**
 *
 * 🚩 AC-14 第1条原前置「走批量导入料号抽屉添加一个产品」**已不可执行**：
 *    主线 2026-09-10 通知 `BulkImportPartsDrawer` 组件本体全工程零渲染、且 F-7 正在删除它。
 *    ⇒ 改用**现存唯一的加产品入口**「从已有产品添加」，断言添加当场无「模板:」徽标。
 *    该入口从不设置 `templateName`，所以它证明的是「渲染层已无模板徽标」这一半；
 *    「唯一赋值点被删」那一半由 F-7 的源码改动本身闭合（源码级断言超出本片可读范围，见回报）。
 *
 * ── 写入面 ────────────────────────────────────────────────────────────
 *   自建 `T260910-` 前缀报价单（走 POST /api/cpq/quotations）+ 经 UI 加一个产品行。
 *   🚦 报价单不删（级联多表、命中面说不清）⇒ 登记「待回收清单」交主线。
 */
import { test, expect } from '@playwright/test';
import * as H from './t260910.helpers';

const CUST0004 = '1f5818d8-b934-44be-b1c3-47df7053cff4';

test.describe.configure({ mode: 'serial' });

let qid = '', qnum = '';

test('S3-0 · 自建一张报价单（CUST-0004）', async () => {
  const cookie = await H.loginApi();
  const catId = H.sqlScalar(`SELECT product_category_id::text FROM customer WHERE code='CUST-0004'`);
  expect(catId, '前置未满足：CUST-0004 无产品分类').toMatch(/^[0-9a-f-]{36}$/);
  const tplId = H.sqlScalar(`SELECT id::text FROM template WHERE template_kind='QUOTATION' AND status='PUBLISHED'
      AND category_id='${catId}' ORDER BY updated_at DESC LIMIT 1`);
  expect(tplId, '前置未满足：该分类下无 PUBLISHED 报价模板 ⇒ 判【未验证】').toMatch(/^[0-9a-f-]{36}$/);
  // 🩹 复用本片上一轮已建的单，避免每次重跑都新增一张孤儿报价单（待回收清单越滚越长）
  const reuse = H.sqlRows(`SELECT id::text id, quotation_number qn FROM quotation
     WHERE name LIKE '${H.TAG}AC15-%' ORDER BY created_at DESC LIMIT 1`)[0];
  if (reuse?.id) {
    qid = reuse.id; qnum = reuse.qn;
    console.log(`[复用自建单] ${qnum} ${qid}`);
    return;
  }
  const name = `${H.TAG}AC15-${Date.now().toString(36).slice(-6)}`;
  const res = await fetch(`${H.BACKEND_URL}/api/cpq/quotations`, {
    method: 'POST', headers: { Cookie: cookie, 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ customerId: CUST0004, name, quoteType: 'STANDARD', categoryId: catId, customerTemplateId: tplId }),
  });
  const body: any = await res.json().catch(() => null);
  expect(res.status, `建单失败 ${res.status} ${JSON.stringify(body).slice(0, 300)}`).toBeLessThan(300);
  qid = body?.data?.id ?? body?.id; qnum = body?.data?.quotationNumber ?? body?.quotationNumber;
  expect(qid, `建单没拿到 id`).toMatch(/^[0-9a-f-]{36}$/);
  H.appendEvidence('92-待回收清单.txt',
    `${new Date().toISOString()} 自建报价单 ${qnum} (${qid}) name=${name} —— 🚦 本片不自行删除（级联多表，命中面说不清），交主线在闸门 B 呈报用户批准\n`);
  console.log(`[自建单] ${qnum} ${qid}`);
});

test('S3-1 · AC-15③ 「添加产品」下拉仍为两项 + AC-14① 添加当场无「模板:」徽标', async ({ page }) => {
  expect(qid, '前置：S3-0 没建出单').not.toBe('');
  await H.uiLogin(page);
  await page.goto(`/quotations/${qid}/edit`);
  await page.waitForSelector('.ant-steps-item', { timeout: 90_000 });
  const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(next, 'Step1「下一步」不可点 ⇒ **夹具问题**，判【未验证】').toBeEnabled({ timeout: 40_000 });
  await next.click();
  await page.waitForTimeout(9000);

  // ── AC-15③ 下拉项 ────────────────────────────────────────────────
  const addBtn = page.locator('button').filter({ hasText: /添加产品/ }).first();
  await expect(addBtn, '找不到「添加产品」入口 ⇒ **入口问题**，判【未验证】').toBeVisible({ timeout: 30_000 });
  await addBtn.click();
  await page.waitForTimeout(2500);
  const menu = (await page.locator('.ant-dropdown:visible .ant-dropdown-menu-item').allInnerTexts())
    .map((s) => s.replace(/\s+/g, '')).filter(Boolean);
  console.log('[添加产品菜单] ' + JSON.stringify(menu));
  H.writeEvidence('ac15-添加产品下拉.txt', `菜单项=${JSON.stringify(menu)}\n期望=["从已有产品添加","选配添加"]\n`);
  expect(menu.length, `AC-15③「添加产品」下拉应为 2 项，实际 ${menu.length} 项：${JSON.stringify(menu)}`).toBe(2);
  expect(menu.join('|'), 'AC-15③ 应含「从已有产品添加」').toContain('从已有产品添加');
  expect(menu.join('|'), 'AC-15③ 应含「选配添加」').toContain('选配添加');
  expect(menu.join('|'), 'AC-15③ 🚫 不得再有批量导入料号入口（F-7 已删组件本体）').not.toMatch(/批量|导入/);
  await H.shot(page, 'ac15-添加产品下拉两项');

  // ── AC-14① 经「从已有产品添加」加一行 → 当场无「模板:」徽标 ──────────
  // 🩹 harness 修复：读完菜单文案后下拉可能已收起 ⇒ 重新点开再选；
  //    且该入口实测是 **Modal 不是 Drawer**（首轮按 .ant-drawer-content 找了 30s 找不到，
  //    那种失败长得像「入口坏了」，实际是**我猜错了容器类型**）。两种都接受。
  if (await page.locator('.ant-dropdown:visible .ant-dropdown-menu-item').count() === 0) {
    await addBtn.click();
    await page.waitForTimeout(2000);
  }
  await page.locator('.ant-dropdown:visible .ant-dropdown-menu-item').filter({ hasText: /从已有产品添加/ }).first().click();
  await page.waitForTimeout(8000);
  const drawer = page.locator('.ant-drawer').first();  // 🩹 实测该 antd 版本**没有** .ant-drawer-content 内层类
  await expect(drawer, '「从已有产品添加」弹层没打开（Drawer/Modal 都没找到）⇒ **入口问题**，判【未验证】').toBeVisible({ timeout: 40_000 });
  console.log('[弹层类型] ' + await page.evaluate(() => {
    const d = document.querySelector('.ant-drawer'), m = document.querySelector('.ant-modal');
    return `drawer=${!!d} modal=${!!m} title=${((d || m) as HTMLElement)?.innerText?.replace(/\s+/g, ' ').slice(0, 120)}`;
  }));
  const rows = drawer.locator('.ant-table-row');
  const n = await rows.count();
  console.log('[抽屉候选行数] ' + n);
  expect(n, `抽屉候选列表为空 ⇒ 断言会空跑（testing.md §5.5 ③），判【未验证】`).toBeGreaterThan(0);
  const cb = rows.first().locator('input[type="checkbox"]').first();
  await expect(cb, '抽屉行里找不到勾选框 ⇒ **入口问题**').toBeVisible({ timeout: 15_000 });
  await cb.click();
  await page.waitForTimeout(1500);
  const ok = drawer.locator('button').filter({ hasText: /加\s*入\s*报\s*价\s*单/ }).last();  // 🩹 实测按钮文案是「加入报价单」（antd 两字按钮会插空格，见 CLAUDE.md 记忆）
  await expect(ok, '抽屉里找不到确认按钮 ⇒ **入口问题**').toBeVisible({ timeout: 15_000 });
  await ok.click();
  await page.waitForTimeout(15000);

  const cards = await H.readCards(page);
  console.log('[添加后卡片] ' + JSON.stringify(cards.map((c) => [c.leftTexts, c.rightTexts, c.templateBadgeTexts])));
  expect(cards.length, 'AC-14① 添加后应至少 1 张卡片，0 张则断言空跑').toBeGreaterThan(0);
  const moban = await page.locator('.qt-product-card').locator('text=/模板\s*[:：]/').count();
  expect(moban, `AC-14① 添加当场卡片头部不得出现「模板: xxx」徽标，实际 ${moban} 处`).toBe(0);
  await H.shot(page, 'ac14-添加当场无模板徽标');
  H.writeEvidence('ac14-添加当场.txt',
    `自建单=${qnum}\n抽屉候选行数=${n}\n添加后卡片=${JSON.stringify(cards, null, 1)}\n模板徽标计数=${moban}\n`);
});
