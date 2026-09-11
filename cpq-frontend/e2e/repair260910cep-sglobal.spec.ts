/**
 * repair-260910 · S-全局片 · UI 用例
 * 认领：AC-5（无策略元素显示空而非 0）/ AC-6（已提交单冻结值不被改动）/ AC-10（自检）
 * 造数前缀：R260910-G-
 *
 * 🚫 不读实现源码（cpq-backend/src/main/java、cpq-frontend/src）。选择器词汇来自既有 e2e 代码
 *    （t260910-s3-addproduct.spec.ts / repair260910cep-s2.spec.ts）+ dev-docs/main-api.md 契约。
 *
 * 用法（由 shell 编排，一次只跑一个 mode）：
 *   G_MODE=create  G_NAME=R260910-G-001 G_OUT=xx.json   → 建单 + 加产品 + 保存 + 读核价侧
 *   G_MODE=read    G_QID=<uuid>         G_OUT=xx.json   → 只读某单的核价侧（编辑页）
 *   G_MODE=detail  G_QID=<uuid>         G_OUT=xx.json   → 只读某单的核价侧（详情页，已提交单用）
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';

const BACKEND = process.env.PW_BACKEND_URL || 'http://localhost:8099';
const EV = '../dev-docs/task-260908-取数配置器优化/repair-260910-核价侧无销售料号子件取不到元素单价/证据';
const MODE = process.env.G_MODE || 'read';
const QID = process.env.G_QID || '';
const QNAME = process.env.G_NAME || 'R260910-G-000';
const OUT = process.env.G_OUT || `${EV}/SG-out.json`;
const SHOT = process.env.G_SHOT || '';
const CUST0001 = '9ffbf636-dfe5-4c63-8540-0b06a7dc785e'; // 正泰 CUST-0001（实查）

type Row = Record<string, string>;

async function loginApi(): Promise<string> {
  const res = await fetch(`${BACKEND}/api/cpq/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'Admin@2026' }), redirect: 'manual',
  });
  expect(res.ok, `API 登录失败 ${res.status}`).toBeTruthy();
  const raw: string[] = (res.headers as any).getSetCookie?.() ?? [res.headers.get('set-cookie') || ''];
  const jar = raw.filter(Boolean).map((c) => c.split(';')[0].trim());
  expect(jar.length, '登录 200 但无 Set-Cookie').toBeGreaterThan(0);
  return jar.join('; ');
}

async function uiLogin(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products)/, { timeout: 60_000 });
}

/** 读「材质元素」表：按表头文案动态求列下标，🚫 不写死 nth()（沿用 S-2 踩出的口径） */
async function readTable(page: Page): Promise<{ headers: string[]; rows: Row[]; subtotal: Row | null; totalRow: Row | null }> {
  const table = page.locator('table').filter({ hasText: '元素代码' }).first();
  await table.waitFor({ timeout: 60_000 });
  const headers = (await table.locator('thead th').allInnerTexts()).map((s) => s.trim());
  const trs = table.locator('tbody tr, tfoot tr');
  const n = await trs.count();
  const rows: Row[] = [];
  let subtotal: Row | null = null;
  let totalRow: Row | null = null;
  for (let i = 0; i < n; i++) {
    const cells = (await trs.nth(i).locator('td').allInnerTexts()).map((s) => s.trim());
    if (!cells.length) continue;
    const isSubtotal = cells.some((c) => c.includes('小计'));
    const isTotal = cells.some((c) => c.includes('合计'));
    // 🩹 量具修复（详情页实测）：详情页首列「版本」是 rowSpan 合并列，组内第 2 行起**少一个 td**，
    //    按 headers[j] 正序映射会整行左移一格，读出来像「元素单价=640」——那是**量具错位**不是产品缺陷。
    //    数据行改为按右对齐映射（缺的列一定在最左边的合并列）；小计/合计行走 colspan，保持正序。
    const hdr = (!isSubtotal && !isTotal && cells.length < headers.length)
      ? headers.slice(headers.length - cells.length) : headers;
    const o: Row = {};
    hdr.forEach((h, j) => { if (h) o[h] = cells[j] ?? ''; });
    if (isSubtotal) subtotal = o; else if (isTotal) totalRow = o; else rows.push(o);
  }
  return { headers, rows, subtotal, totalRow };
}

/** 进核价单视图 + 切页签。⚠️「📊 核价单」带 emoji 前缀，exact:true 匹配不上 */
async function gotoCostingTab(page: Page, tabName: string) {
  const body = await page.locator('body').innerText();
  if (!body.includes('核价单')) {
    const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
    await next.waitFor({ timeout: 90_000 });
    await expect(next, 'Step1「下一步」不可点 ⇒ 入口/夹具问题，判【未验证】').toBeEnabled({ timeout: 60_000 });
    await next.click();
    await page.waitForTimeout(6000);
  }
  const costing = page.getByText('核价单', { exact: false }).first();
  await costing.waitFor({ timeout: 60_000 });
  await costing.click();
  await page.waitForTimeout(3500);
  const tab = page.getByText(tabName, { exact: true }).first();
  await tab.waitFor({ timeout: 60_000 });
  await tab.click();
  await page.waitForTimeout(3000);
}

function dump(obj: any) {
  fs.mkdirSync(OUT.replace(/\/[^/]+$/, ''), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(obj, null, 1), 'utf-8');
  console.log(`[out] → ${OUT}`);
  console.log(JSON.stringify(obj, null, 1));
}

test.describe('repair-260910 S-全局', () => {
  test('G · 建单/读数（mode=' + MODE + '）', async ({ page }) => {
    // 本机内存吃紧（S-2 实测 Playwright+Chrome 冷启 ~130s），预算必须覆盖启动开销
    test.setTimeout(900_000);

    let qid = QID;
    let qnum = '';

    if (MODE === 'create') {
      // ── 建单：走应用自身的 POST /api/cpq/quotations（🚫 不是 DB 直插），
      //    模板 id 取自 UI 同款 auto-defaults，与用户点「新建」时后端拿到的是同一组
      const cookie = await loginApi();
      const ad: any = await (await fetch(`${BACKEND}/api/cpq/templates/auto-defaults?customerId=${CUST0001}`, { headers: { Cookie: cookie } })).json();
      const d = ad?.data;
      console.log('[auto-defaults]', JSON.stringify(d));
      expect(d?.costingTemplateId, '前置未满足：auto-defaults 没给出核价模板 ⇒ 判【未验证】').toBeTruthy();
      const res = await fetch(`${BACKEND}/api/cpq/quotations`, {
        method: 'POST', headers: { Cookie: cookie, 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify({
          customerId: CUST0001, name: QNAME, projectName: QNAME, quoteType: 'STANDARD',
          customerTemplateId: d.customerTemplateId, costingTemplateId: d.costingTemplateId,
        }),
      });
      const body: any = await res.json().catch(() => null);
      expect(res.status, `建单失败 ${res.status} ${JSON.stringify(body).slice(0, 300)}`).toBeLessThan(300);
      qid = body?.data?.id; qnum = body?.data?.quotationNumber;
      expect(qid, '建单没拿到 id').toMatch(/^[0-9a-f-]{36}$/);
      console.log(`[建单] ${qnum} ${qid} name=${QNAME}`);
    }

    expect(qid, '没有 quotationId').toMatch(/^[0-9a-f-]{36}$/);
    await uiLogin(page);

    if (MODE === 'detail') {
      await page.goto(`/quotations/${qid}`);
    } else {
      await page.goto(`/quotations/${qid}/edit`);
    }
    await page.waitForLoadState('networkidle').catch(() => {});
    await page.waitForTimeout(4000);

    if (MODE === 'prep') {
      // ── 给自己造的单打前缀标记（Step1 改名）+ 走一次 UI 保存 ──
      await page.waitForSelector('.ant-steps-item', { timeout: 90_000 });
      const nameInput = page.locator('input[placeholder*="报价单名称"]').first();
      await expect(nameInput, '找不到「报价单名称」输入框 ⇒ 入口问题，判【未验证】').toBeVisible({ timeout: 60_000 });
      await nameInput.fill(QNAME);
      await page.waitForTimeout(800);
      console.log('[改名] → ' + await nameInput.inputValue());
      const next0 = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
      await expect(next0, 'Step1「下一步」不可点').toBeEnabled({ timeout: 60_000 });
      await next0.click();
      await page.waitForTimeout(10000);
      let draftPut = 0;
      page.on('response', (r) => { if (/\/quotations\/[^/]+\/draft/.test(r.url())) draftPut += 1; });
      const saveBtn = page.locator('button').filter({ hasText: /^\s*保\s*存/ }).first();
      await expect(saveBtn, '找不到保存按钮 ⇒ 入口问题').toBeVisible({ timeout: 60_000 });
      console.log('[保存按钮] ' + (await saveBtn.innerText()).replace(/\s+/g, ''));
      await saveBtn.click();
      await page.waitForTimeout(30000);
      console.log('[draft PUT 次数] ' + draftPut);
    }

    if (MODE === 'create' || MODE === 'add') {
      // ── Step2 加产品：从已有产品添加 → 勾 A002/S0001 → 加入报价单 ──
      await page.waitForSelector('.ant-steps-item', { timeout: 90_000 });
      const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
      await expect(next, 'Step1「下一步」不可点 ⇒ 夹具问题，判【未验证】').toBeEnabled({ timeout: 60_000 });
      await next.click();
      await page.waitForTimeout(8000);

      const addBtn = page.locator('button').filter({ hasText: /添加产品/ }).first();
      await expect(addBtn, '找不到「添加产品」入口 ⇒ 入口问题，判【未验证】').toBeVisible({ timeout: 60_000 });
      await addBtn.click();
      await page.waitForTimeout(2500);
      await page.locator('.ant-dropdown:visible .ant-dropdown-menu-item').filter({ hasText: /从已有产品添加/ }).first().click();
      await page.waitForTimeout(9000);

      const drawer = page.locator('.ant-drawer').first();
      await expect(drawer, '「从已有产品添加」弹层没打开 ⇒ 入口问题，判【未验证】').toBeVisible({ timeout: 60_000 });
      const rows = drawer.locator('.ant-table-row');
      const n = await rows.count();
      console.log('[抽屉候选行数] ' + n);
      expect(n, '抽屉候选为空 ⇒ 断言会空跑，判【未验证】').toBeGreaterThan(0);
      // 🔑 必须挑 S0001（它的核价 BOM 树含无销售料号的 300013/300015），挑错行会验不到本缺陷
      const target = rows.filter({ hasText: 'S0001' }).first();
      await expect(target, '抽屉里找不到 S0001 行 ⇒ 夹具问题，判【未验证】').toBeVisible({ timeout: 30_000 });
      console.log('[选中行] ' + (await target.innerText()).replace(/\s+/g, ' ').slice(0, 200));
      await target.locator('input[type="checkbox"]').first().click();
      await page.waitForTimeout(1500);
      const ok = drawer.locator('button').filter({ hasText: /加\s*入\s*报\s*价\s*单/ }).last();
      await expect(ok, '抽屉里找不到「加入报价单」⇒ 入口问题').toBeVisible({ timeout: 20_000 });
      await ok.click();
      await page.waitForTimeout(20000);

      const cards = await page.locator('.qt-product-card').count();
      console.log('[添加后卡片数] ' + cards);
      expect(cards, '添加后卡片数应 > 0，0 张则后面断言全空跑').toBeGreaterThan(0);

      // 保存草稿（让 costing_card_values 真正落库；🚫 不保存就读，读到的可能只是内存态）
      let draftPut = 0;
      page.on('response', (r) => { if (/\/quotations\/[^/]+\/draft/.test(r.url())) draftPut += 1; });
      const saveBtn = page.locator('button').filter({ hasText: /^\s*保\s*存/ }).first();
      if (await saveBtn.count()) {
        console.log('[保存按钮] ' + (await saveBtn.innerText()).replace(/\s+/g, ''));
        await saveBtn.click();
        await page.waitForTimeout(25000);
      }
      console.log('[draft PUT 次数] ' + draftPut);
    }

    // ── 读核价侧「材质元素」──
    await gotoCostingTab(page, '材质元素');
    const { headers, rows, subtotal, totalRow } = await readTable(page);
    const out = { mode: MODE, qid, qnum, name: QNAME, at: new Date().toISOString(), headers, rows, subtotal, totalRow };
    dump(out);
    if (SHOT) { fs.mkdirSync(EV, { recursive: true }); await page.screenshot({ path: `${EV}/${SHOT}.png` }); console.log(`[shot] ${EV}/${SHOT}.png`); }

    // 🔒 非空保护：本用例只负责「把 UI 真实读数取回来」，判定在报告里按 AC 原文做；
    //    但「表根本没渲染出来」必须当场红，否则后续一切断言都是空跑。
    expect(headers.length, 'UI 量具失效：表头为空 ⇒ 判【未验证】').toBeGreaterThan(0);
    expect(rows.length, 'UI 量具失效：数据行为 0 ⇒ 判【未验证】').toBeGreaterThan(0);
  });
});
