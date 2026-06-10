/**
 * 专项 E2E：行键放开输入字段 + 录入实时判重 (2026-06-09)
 *
 * 三个用例：
 *  TC-1  组件管理 INPUT_TEXT/INPUT_NUMBER 字段可勾行键（eligible=true, source=input），
 *        FORMULA 字段 disabled（eligible=false）。
 *        断言方式：后端候选端点 /row-key-candidates 返回 eligible + source，
 *        配合组件管理页面截图作为视觉佐证（Checkbox disabled 直接由 eligible 控制）。
 *
 *  TC-2  报价 Step2 同组件同组合键两行时 <tr data-rowkey-dup="1"> 出现（实时标红）。
 *        通过 API 预置 DRAFT 报价单含两行同料件，编辑页 Step2「来料」Tab 验证标红。
 *
 *  TC-3  提交含重复行键 → 422 + body 含"行键重复"；改唯一键 → 期望 200。
 *        纯 API 方式（request fixture）。
 *
 * 测试数据：
 *  - 组件「投料成本」(52a0bfde-cfe8-4cf9-9c12-062fd5255ad8)
 *      INPUT_NUMBER「单价(USD/Kg)」eligible=true, source=input
 *      FORMULA「金额」eligible=false
 *  - 组件「来料」(3cb220be-c6d2-4956-8031-ec7ec798689d)
 *      row_key_fields=["料件"]（beforeAll 通过 API 设置）
 *  - 模板「报价模板0608」(ab594b45-9c42-46bc-a678-96af3eb3eb40)
 *  - 客户「罗克韦尔」(3027d83b-d412-407d-ae43-5d513fed7b1e)
 */
import { test, expect, Page, APIRequestContext } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

const BACKEND = 'http://localhost:8081';
const COMP_LAOLIAO_ID = '3cb220be-c6d2-4956-8031-ec7ec798689d';   // 来料
const COMP_TOULIAO_ID = '52a0bfde-cfe8-4cf9-9c12-062fd5255ad8';   // 投料成本
const TEMPLATE_ID = 'ab594b45-9c42-46bc-a678-96af3eb3eb40';       // 报价模板0608
const CUSTOMER_ID = '3027d83b-d412-407d-ae43-5d513fed7b1e';       // 罗克韦尔

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `rk-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`[shot] ${name} -> ${path.basename(file)}`);
}

/** 登录取 cookie（使用 API auth 端点）。 */
async function loginAndGetCookie(): Promise<string> {
  const res = await fetch(`${BACKEND}/api/cpq/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'Admin@2026' }),
  });
  const setCookie = res.headers.get('set-cookie') ?? '';
  const match = setCookie.match(/CPQ_SESSION=([^;]+)/);
  return match ? `CPQ_SESSION=${match[1]}` : '';
}

/** 幂等设置「来料」组件 row_key_fields。 */
async function setLaoliaoRowKeyFields(cookie: string, fields: string[]) {
  const compRes = await fetch(`${BACKEND}/api/cpq/components/${COMP_LAOLIAO_ID}`, {
    headers: { Cookie: cookie },
  });
  const compJson = await compRes.json();
  const comp = compJson.data;
  const payload = {
    name: comp.name,
    code: comp.code,
    fields: comp.fields,
    formulas: comp.formulas ?? [],
    componentType: comp.componentType ?? 'NORMAL',
    dataDriverPath: comp.dataDriverPath ?? '',
    rowKeyFields: fields,
    status: comp.status ?? 'ACTIVE',
  };
  const res = await fetch(`${BACKEND}/api/cpq/components/${COMP_LAOLIAO_ID}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json; charset=utf-8', Cookie: cookie },
    body: JSON.stringify(payload),
  });
  const json = await res.json();
  if (json.code !== 200) throw new Error(`setLaoliaoRowKeyFields failed: ${json.message}`);
}

/**
 * 创建 DRAFT 报价单 + saveDraft 两行手动行（_origin=manual）到「来料」tab。
 * 两行的「料件」值由调用方传入（可相同 = 重复键，可不同 = 唯一键）。
 * 返回 quotationId。
 */
async function createDraftWithManualRows(
  cookie: string,
  料件1: string,
  料件2: string,
): Promise<string> {
  // 1. 创建报价单（绑定模板，saveDraft 时 ensureStructure 会读到模板构建结构含 rowKeyFields）
  const createRes = await fetch(`${BACKEND}/api/cpq/quotations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8', Cookie: cookie },
    body: JSON.stringify({
      customerId: CUSTOMER_ID,
      name: `E2E-rowkey-${Date.now()}`,
      templateId: TEMPLATE_ID,
    }),
  });
  const createJson = await createRes.json();
  if (createJson.code !== 200) throw new Error(`createQuotation failed: ${createJson.message}`);
  const quotationId: string = createJson.data.id;

  // 2. saveDraft：添加 lineItem + 来料 tab 两行手动行
  const draftPayload = {
    customerTemplateId: TEMPLATE_ID,
    lineItems: [{
      productName: '测试产品',
      sortOrder: 0,
      componentData: [{
        componentId: COMP_LAOLIAO_ID,
        tabName: '来料',
        sortOrder: 1,
        rowData: JSON.stringify([
          { _origin: 'manual', '料件': 料件1, '类型': 'A', '组成用量': '1', '单位': 'kg' },
          { _origin: 'manual', '料件': 料件2, '类型': 'B', '组成用量': '2', '单位': 'kg' },
        ]),
      }],
    }],
  };
  const draftRes = await fetch(`${BACKEND}/api/cpq/quotations/${quotationId}/draft`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json; charset=utf-8', Cookie: cookie },
    body: JSON.stringify(draftPayload),
  });
  const draftJson = await draftRes.json();
  if (draftJson.code !== 200) throw new Error(`saveDraft failed: ${draftJson.message}`);
  return quotationId;
}

// ─────────────────────────────────────────────────────────────────────────────

let backendUp = false;
let apiCookie = '';

test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (!backendUp) return;
  apiCookie = await loginAndGetCookie();
  if (!apiCookie) throw new Error('无法获取 API session cookie');
  await setLaoliaoRowKeyFields(apiCookie, ['料件']);
  console.log('[beforeAll] 「来料」组件 row_key_fields=["料件"] 已设置');
});

// ─────────────────────────────────────────────────────────────────────────────
// TC-1: 候选端点验证 INPUT 字段 eligible=true / FORMULA eligible=false
//       + 组件管理页面截图（视觉佐证）
// ─────────────────────────────────────────────────────────────────────────────
test('TC-1 行键候选：INPUT_NUMBER eligible=true(source=input)，FORMULA eligible=false', async ({ page, request }) => {
  test.skip(!backendUp, '后端未启动');

  // ── 部分 A：通过后端候选端点断言 eligible / source ──
  // 「投料成本」组件：INPUT_NUMBER「单价(USD/Kg)」 + FORMULA「金额」
  const res = await request.post(
    `${BACKEND}/api/cpq/components/${COMP_TOULIAO_ID}/row-key-candidates`,
    {
      data: {
        fields: [
          { name: '单价(USD/Kg)', field_type: 'INPUT_NUMBER' },
          { name: '金额', field_type: 'FORMULA' },
        ],
      },
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        Cookie: apiCookie,
      },
    }
  );
  const data = await res.json();
  console.log('[TC-1] row-key-candidates response:', JSON.stringify(data, null, 2));

  const candidates: Array<{
    fieldName: string; eligible: boolean; source: string | null; reason: string | null;
  }> = data.data?.candidates ?? [];

  const inputCand = candidates.find((c) => c.fieldName === '单价(USD/Kg)');
  const formulaCand = candidates.find((c) => c.fieldName === '金额');

  // 核心断言
  expect(inputCand).toBeDefined();
  expect(inputCand?.eligible, 'INPUT_NUMBER 单价(USD/Kg) 应可作行键（eligible=true）').toBe(true);
  expect(inputCand?.source, 'INPUT_NUMBER 单价(USD/Kg) 行键来源应为 input').toBe('input');

  expect(formulaCand).toBeDefined();
  expect(formulaCand?.eligible, 'FORMULA 金额 不应可作行键（eligible=false）').toBe(false);
  expect(formulaCand?.reason, 'FORMULA 金额 应有 reason 说明').toBeTruthy();

  console.log('[TC-1] INPUT_NUMBER eligible=', inputCand?.eligible, 'source=', inputCand?.source);
  console.log('[TC-1] FORMULA eligible=', formulaCand?.eligible, 'reason=', formulaCand?.reason);

  // ── 部分 B：UI 截图（视觉佐证：FieldConfigTable Checkbox disabled 由 eligible 控制）──
  await loginAsAdmin(page);
  await page.goto('/components');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  await shot(page, 'tc1-components-page');

  // 组件管理页面可以正常加载（断言页面中有组件树容器）
  const leftPanel = page.locator('.cm-left-panel').first();
  await expect(leftPanel).toBeVisible({ timeout: 10000 });
  await shot(page, 'tc1-left-panel-visible');

  console.log('[TC-1] 组件管理页面加载正常，候选端点断言通过');
});

// ─────────────────────────────────────────────────────────────────────────────
// TC-2: 报价 Step2 来料 Tab 同料件两行 → data-rowkey-dup="1" 存在
// ─────────────────────────────────────────────────────────────────────────────
test('TC-2 报价 Step2：同组件重复组合键行带 data-rowkey-dup="1"', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  // 通过 API 预置：DRAFT 报价单，来料 tab 两行同料件（铜材 / 铜材）
  const quotationId = await createDraftWithManualRows(apiCookie, '铜材', '铜材');
  console.log(`[TC-2] 测试报价单 ID: ${quotationId}`);

  const consoleErrors: string[] = [];
  page.on('console', (m) => {
    const t = m.text();
    if (m.type() === 'error') consoleErrors.push(t);
  });

  await loginAsAdmin(page);

  // 进入报价单编辑页（QuotationWizard 编辑模式）
  await page.goto(`/quotations/${quotationId}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
  await shot(page, 'tc2-step1-loaded');

  // QuotationWizard 编辑模式加载后停在 Step1，需要点「下一步」进入 Step2
  const nextBtn = page.getByRole('button', { name: /下一步/ }).first();
  if (await nextBtn.isVisible({ timeout: 5000 }).catch(() => false)) {
    await nextBtn.click();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);
    console.log('[TC-2] 已点击「下一步」进入 Step2');
  }
  await shot(page, 'tc2-step2');

  // 统计 qt-tab-btn 数量（确认已进入 Step2 并有产品卡片）
  const tabs = page.locator('button.qt-tab-btn');
  const tabCount = await tabs.count();
  console.log(`[TC-2] qt-tab-btn 数量: ${tabCount}`);

  // 点击「来料」Tab
  const laoliaoTab = tabs.filter({ hasText: '来料' }).first();
  const laoliaoVisible = await laoliaoTab.isVisible({ timeout: 5000 }).catch(() => false);
  if (laoliaoVisible) {
    await laoliaoTab.click();
    await page.waitForTimeout(2000);
    console.log('[TC-2] 已点击「来料」Tab');
  } else {
    console.log('[TC-2] 未找到「来料」Tab，所有 Tab:', await tabs.allInnerTexts().catch(() => []));
  }
  await shot(page, 'tc2-laoliao-tab');

  // 等待数据渲染（手动行判重是同步计算，应立即出现）
  await page.waitForTimeout(1500);

  // 核心断言：至少 2 行带 data-rowkey-dup="1"
  const dupRows = page.locator('[data-rowkey-dup="1"]');
  const dupCount = await dupRows.count();
  console.log(`[TC-2] data-rowkey-dup="1" 行数: ${dupCount}`);
  await shot(page, 'tc2-dup-rows');

  expect(dupCount, '应有至少 2 行 data-rowkey-dup="1"（两行同「料件」值）').toBeGreaterThanOrEqual(2);

  // 验证 title 属性含"行键重复"
  const firstDupTitle = await dupRows.first().getAttribute('title');
  console.log(`[TC-2] 首行 data-rowkey-dup title: ${firstDupTitle}`);
  expect(firstDupTitle, 'title 应包含"行键重复"').toContain('行键重复');

  const bizErrors = consoleErrors.filter(
    (e) => !e.includes('[antd:') && !e.includes('Warning:') && !e.includes('<form>')
  );
  console.log(`[TC-2] 业务 console.error: ${bizErrors.length}`);
});

// ─────────────────────────────────────────────────────────────────────────────
// TC-3: 提交硬拦（纯 API）
// ─────────────────────────────────────────────────────────────────────────────
test('TC-3 提交硬拦：重复键 → 422，唯一键 → 200', async ({ request }) => {
  test.skip(!backendUp, '后端未启动');

  // ── 场景 A：两行同料件（重复键）→ 422 ──
  const dupId = await createDraftWithManualRows(apiCookie, '铜材A', '铜材A');
  console.log(`[TC-3] 重复键报价单: ${dupId}`);

  const dupSubmitRes = await request.post(
    `${BACKEND}/api/cpq/quotations/${dupId}/submit`,
    { headers: { 'Content-Type': 'application/json', Cookie: apiCookie } }
  );
  const dupBody = await dupSubmitRes.json();
  console.log(`[TC-3] 重复键 submit code=${dupBody.code} message=${dupBody.message?.slice(0, 120)}`);

  expect(dupBody.code, '重复行键提交应返回 422').toBe(422);
  expect(dupBody.message, 'message 应含"行键重复"').toContain('行键重复');

  // ── 场景 B：两行不同料件（唯一键）→ 200 ──
  const uniqueId = await createDraftWithManualRows(apiCookie, '铜材B', '铝材B');
  console.log(`[TC-3] 唯一键报价单: ${uniqueId}`);

  const uniqueSubmitRes = await request.post(
    `${BACKEND}/api/cpq/quotations/${uniqueId}/submit`,
    { headers: { 'Content-Type': 'application/json', Cookie: apiCookie } }
  );
  const uniqueBody = await uniqueSubmitRes.json();
  console.log(`[TC-3] 唯一键 submit code=${uniqueBody.code}`);

  expect(uniqueBody.code, '唯一行键提交应返回 200').toBe(200);
});
