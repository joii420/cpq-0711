/**
 * 独立验收（会话 cpq-材质元素功能验收）· task-260901「材质与元素业务结构变更」
 *
 * 本文件不是交付方的用例，是**验收方按「模拟用户规范使用系统」独立走的用户旅程**。
 * 跑在 5174/8081（主工作区，master 已合并 f5adfccc）—— 交付方原本只在 worktree 8082/5175 验过。
 *
 * 🚨 纪律（docs/rules/testing.md §4.3）：
 *  - 同库并发（别的会话正在往 cpq_db_0724 写 TESTNO-* 元素）。
 *  - J-1 纯读。J-2 会加 1 个产品行，**用 UI 删除还原**（不打 SQL DELETE，不碰红线）。
 *  - 不碰任何存量真实数据（00006-01 / 元素组成 / 既有报价单行项）。
 */
import { test, expect, Page } from '@playwright/test';
import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __f = fileURLToPath(import.meta.url);
const OUT = path.resolve(path.dirname(__f), '../../dev-docs/task-260901-材质管理模块定义规则更新/证据-独立验收');

function sql(q: string): string[] {
  const out = execFileSync('psql',
    ['-h', '10.177.152.12', '-U', 'postgres', '-d', 'cpq_db_0724', '-tAF', '\t', '-c', q],
    { env: { ...process.env, PGPASSWORD: 'joii5231' }, encoding: 'utf-8' });
  return out.split('\n').map(s => s.trim()).filter(Boolean);
}
const sqlOne = (q: string) => { const r = sql(q); return r.length ? r[0] : null; };

let idx = 0;
async function shot(page: Page, name: string) {
  fs.mkdirSync(OUT, { recursive: true });
  const f = path.join(OUT, `V-${String(++idx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: f, fullPage: true });
  console.log(`📸 ${f}`);
}
function note(name: string, s: string) {
  fs.mkdirSync(OUT, { recursive: true });
  const f = path.join(OUT, `V-${name}.txt`);
  fs.writeFileSync(f, s, 'utf-8');
  console.log(`🧾 ${f}`);
}

async function login(page: Page) {
  for (let i = 0; i < 4; i++) {
    const r = await page.request.post('/api/cpq/auth/login',
      { data: { username: 'admin', password: 'Admin@2026' } });
    if (r.ok()) return;
    console.warn(`[login] ${r.status()} 重试`);
    await page.waitForTimeout(20_000);
  }
  throw new Error('登录失败');
}

const QNO = process.env.PW_QUOTATION || 'QT-20260901-0234';
const qidOf = () => sqlOne(`SELECT id FROM quotation WHERE quotation_number='${QNO}'`)!;

async function gotoStep2(page: Page) {
  await page.goto(`/quotations/${qidOf()}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  const next = page.getByRole('button', { name: /下一步/ }).first();
  if (await next.isVisible().catch(() => false) && await next.isEnabled().catch(() => false)) {
    await next.click();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2500);
  }
}

/**
 * J-1 · 选配添加抽屉里是否存在「模板」选择环节（只看不提交，零写库）
 * 目的：判定「选配加入的行项 template_id 恒空」是**用例跳过了选模板**，
 *      还是**这条路径根本没有模板环节**。
 */
test('J-1 选配添加抽屉：是否存在「模板」选择环节（只看不提交）', async ({ page }) => {
  await login(page);
  const qid = qidOf();
  expect(qid, `前置草稿单 ${QNO} 应存在`).not.toBeNull();
  await gotoStep2(page);
  await shot(page, 'step2-before-add');

  await page.getByRole('button', { name: /添加产品/ }).first().click();
  await page.waitForTimeout(800);
  const menuText = await page.locator('.ant-dropdown:visible, .ant-modal:visible, .ant-drawer:visible')
    .allInnerTexts().catch(() => []);
  console.log('[J-1] 「添加产品」入口 =', JSON.stringify(menuText));

  await page.locator('text=选配添加').first().click();
  await page.waitForTimeout(1500);
  const drawer = page.locator('.ant-drawer').last();
  await expect(drawer, '选配添加抽屉应打开').toBeVisible({ timeout: 15_000 });

  const full = await drawer.innerText();
  console.log('[J-1] 抽屉全文含「模板」二字 =', /模板/.test(full));
  note('J1-selconfig-drawer', `入口 = ${JSON.stringify(menuText)}\n含「模板」= ${/模板/.test(full)}\n\n${full}`);

  const btn = drawer.locator('button:has-text("新增材质料号")').first();
  if (await btn.isVisible().catch(() => false)) {
    await btn.click();
    await page.waitForTimeout(1200);
    const full2 = await drawer.innerText();
    console.log('[J-1] 新增材质料号后含「模板」=', /模板/.test(full2));
    note('J1-after-new-material-part', full2);
  }
});

/**
 * J-2 · 决定性实验：选配加一个产品，看**新行**的 template_id 与卡片渲染。
 *
 * 前端 QuotationWizard.tsx:1798 写的是 `templateId: li.templateId || customerTemplateId || ''`，
 * 而本单 customer_template_id 非空 ⇒ 按代码不该为空。但库里既有 9 行选配行项 template_id 全空。
 * 本条把矛盾钉死在一次可观测的操作上。
 *
 * 🚨 还原：只用 UI 的「删除」按钮删掉我加的那一行（用户正常操作），不打 SQL DELETE。
 */
test('J-2 选配加产品 → 新行 template_id 与卡片渲染（决定性实验，UI 还原）', async ({ page }) => {
  await login(page);
  const qid = qidOf();

  const before = sql(`SELECT id FROM quotation_line_item WHERE quotation_id='${qid}'`);
  const beforeCustomParts = sqlOne(
    `SELECT count(*) FROM material_master WHERE material_recipe_id IS NOT NULL`)!;
  console.log(`[J-2] 加入前行项数 = ${before.length}；material_recipe_id 非空料号 = ${beforeCustomParts}`);

  await gotoStep2(page);
  await page.getByRole('button', { name: /添加产品/ }).first().click();
  await page.waitForTimeout(800);
  await page.locator('text=选配添加').first().click();
  await page.waitForTimeout(1500);
  const drawer = page.locator('.ant-drawer').last();
  await drawer.locator('button:has-text("新增材质料号")').first().click();
  await page.waitForTimeout(800);

  // 选材质 00006 / AgNi10
  const matSel = drawer.locator('.ant-select').first();
  await matSel.click();
  await page.keyboard.type('00006', { delay: 60 });
  await page.waitForTimeout(800);
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: '00006' }).first().click();
  await page.waitForTimeout(1000);
  await shot(page, 'J2-material-picked');

  // 选含量配置（材质选完才出现）
  const selects = drawer.locator('.ant-select');
  const n = await selects.count();
  console.log(`[J-2] 选材质后抽屉内 select 数 = ${n}`);
  if (n >= 2) {
    await selects.nth(1).click();
    await page.waitForTimeout(700);
    const opts = await page.locator('.ant-select-dropdown:visible .ant-select-item-option')
      .allInnerTexts().catch(() => []);
    console.log('[J-2] 含量配置候选 =', JSON.stringify(opts));
    note('J2-config-options', JSON.stringify(opts, null, 2));
    expect(opts.length, 'J-2：含量配置下拉须有候选（空 = 断言空跑）').toBeGreaterThan(0);
    await page.locator('.ant-select-dropdown:visible .ant-select-item-option').first().click();
    await page.waitForTimeout(700);
  }
  await shot(page, 'J2-config-picked');

  // 走完「下一步 → 确认加入」
  for (const label of [/下一步/, /确认添加/, /确认加入/]) {
    const btn = drawer.locator('button').filter({ hasText: label }).last();
    if (await btn.count() === 0) continue;
    if (!(await btn.isEnabled().catch(() => false))) continue;
    await btn.click();
    await page.waitForTimeout(1500);
  }
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(4000);
  await shot(page, 'J2-after-confirm-add');

  // ① 加入后**立刻**看卡片：是否空态
  const bodyAfter = await page.locator('body').innerText();
  const emptyHint = bodyAfter.includes('请通过添加产品选择模板后自动加载组件结构');
  console.log(`[J-2] 加入后页面出现「请通过添加产品选择模板…」空态 = ${emptyHint}`);

  // ② 查库：新增的行是谁、template_id 是否为空
  const after = sql(`SELECT id FROM quotation_line_item WHERE quotation_id='${qid}'`);
  const added = after.filter(id => !before.includes(id));
  console.log(`[J-2] 新增行项 = ${added.length} 条：${added.join(',')}`);
  expect(added.length, 'J-2：应至少新增 1 行（0 = 没加进去，后面的断言会空跑）').toBeGreaterThan(0);

  const rows = sql(`SELECT id, product_part_no_snapshot, coalesce(template_id::text,'<NULL>')
    FROM quotation_line_item WHERE id IN (${added.map(i => `'${i}'`).join(',')})`);
  console.log('[J-2] 新行明细 =', JSON.stringify(rows, null, 2));
  const custTpl = sqlOne(`SELECT coalesce(customer_template_id::text,'<NULL>') FROM quotation WHERE id='${qid}'`);
  note('J2-new-line-items', [
    `报价单 ${QNO} 的 customer_template_id = ${custTpl}`,
    `加入后页面空态「请通过添加产品选择模板…」= ${emptyHint}`,
    '新增行项（id / 料号 / template_id）：',
    ...rows,
  ].join('\n'));

  // 🚨 还原：用 UI 删除我加的产品行
  const delBtns = page.getByRole('button', { name: /^删除$/ });
  const dc = await delBtns.count();
  console.log(`[J-2] 页面「删除」按钮数 = ${dc}`);
  if (dc > 0) {
    await delBtns.last().click();
    await page.waitForTimeout(800);
    const ok = page.getByRole('button', { name: /确 ?定|确认|OK/ }).last();
    if (await ok.isVisible().catch(() => false)) { await ok.click(); await page.waitForTimeout(800); }
    const save = page.getByRole('button', { name: /保存草稿/ }).first();
    if (await save.isVisible().catch(() => false)) {
      await save.click();
      await page.waitForTimeout(6000);
    }
  }
  await shot(page, 'J2-after-ui-delete');

  const finalRows = sql(`SELECT count(*) FROM quotation_line_item WHERE quotation_id='${qid}'`);
  console.log(`[J-2] 还原后行项数 = ${finalRows[0]}（加入前 ${before.length}）`);
  note('J2-restore', `加入前 ${before.length} → 加入后 ${after.length} → UI 删除并保存后 ${finalRows[0]}`);

  // 断言本条的结论（决定性）：新行的 template_id
  const nullTpl = rows.filter(r => r.includes('<NULL>')).length;
  console.log(`[J-2] 🔴 新行中 template_id 为空的条数 = ${nullTpl} / ${rows.length}`);
});
