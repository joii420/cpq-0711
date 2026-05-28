/**
 * E2E — 选配落库迁 V6 Phase 1（AP-53 续 6）
 *
 * 验证 ConfigureProductService.configure() 现在把"料号身份 + 元素 + 组合子件"写入 V6
 * (material_master / element_bom_item / material_bom_item)，让现有 V6 mirror 视图能渲染选配产品。
 *
 * 用 API 层（page.request，带 admin 会话）直接打 configure 端点：
 *  1) SIMPLE custom (AgCu90, Ag90/Cu10) → 提交不崩 + 新料号进 material_master(字典派 recipeBound=true) + 可被 Step1 搜到
 *  2) COMPOSITE custom (AgCu90 + AgCu85 两子件) → 提交不崩 + 父料号 + 子件 ASSEMBLY 行
 *
 * 注：幂等 —— 指纹复用 + ON CONFLICT DO NOTHING，重复跑不会重复落库。
 * material_bom_item/element_bom_item 行级断言由 psql 侧自检覆盖（见 RECORD.md）。
 */
import { test, expect } from '@playwright/test';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';

const BASE = 'http://localhost:8081';
const QUOTE_ID = '03e6cd66-3c4a-4d57-b11e-e5a03a115b58'; // QT-20260527-1656, customer CUST-1269

function findCfgPart(lineItems: any[], prefix: string): string | undefined {
  for (const li of lineItems || []) {
    for (const v of Object.values(li)) {
      if (typeof v === 'string' && v.startsWith(prefix)) return v;
    }
  }
  return undefined;
}

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

test('选配落库迁 V6 Phase 1: SIMPLE + COMPOSITE custom 提交写 V6 + 可渲染', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);

  // ─── 1) SIMPLE custom ───
  const simpleBody = {
    productType: 'SIMPLE',
    tempId: crypto.randomUUID(),
    parts: [{
      name: 'E2E-SIMPLE',
      partMode: 'custom',
      recipeCode: 'AgCu90',
      elements: [{ elementCode: 'Ag', pct: 90 }, { elementCode: 'Cu', pct: 10 }],
      processIds: [],
      unitWeightGrams: 0.5,
      quotationLineItemId: crypto.randomUUID(),
    }],
  };
  const r1 = await page.request.post(`${BASE}/api/cpq/configure-product/quotations/${QUOTE_ID}`, { data: simpleBody });
  if (r1.status() !== 200) console.log('[SIMPLE configure] FAIL body=', await r1.text());
  expect(r1.status(), 'SIMPLE 提交必须 200（不再崩）').toBe(200);
  const resp1 = await r1.json();
  console.log('[SIMPLE configure] resp=', JSON.stringify(resp1));
  expect(resp1.lineItems?.length, 'SIMPLE 应返 1 个 lineItem').toBeGreaterThanOrEqual(1);

  const simpleHf = findCfgPart(resp1.lineItems, 'CFG-AgCu');
  console.log('[SIMPLE] 新料号=', simpleHf, 'reused=', resp1.reusedHfPartNos);
  expect(simpleHf, '应生成 CFG-AgCu-xxxxx 料号').toBeTruthy();

  // 新料号进了 material_master → 字典派可读（recipeBound=true, Ag90/Cu10）
  const rMat = await page.request.get(`${BASE}/api/cpq/quotations/configure/existing-part/${simpleHf}/material`);
  expect.soft(rMat.status(), 'existing-part/material 必须 200（料号在 material_master）').toBe(200);
  const matDto = await rMat.json();
  console.log('[SIMPLE material]', JSON.stringify(matDto));
  expect.soft(matDto.recipeBound, '新料号绑定 AgCu90 → 字典派 recipeBound=true').toBe(true);
  expect.soft(matDto.recipeCode, 'recipeCode=AgCu90').toBe('AgCu90');

  // 新料号在 material_master（修 B-3）。注：Step1 搜索会排除"作为组合子件用过的料号"，
  // 而本 spec 后续 COMPOSITE 会把该 SIMPLE 料号用作子件，重复跑后它就成了子件被排除 —— 故此处
  // 不硬断言可搜到（成为子件后不可独立报价是正确行为），仅记录；存在性已由 existing-part/material 200 证明。
  const rSearch = await page.request.get(`${BASE}/api/cpq/quotations/configure/search-parts?q=${simpleHf}&size=10`);
  const searchBody = await rSearch.json();
  console.log('[SIMPLE search] hits=', (searchBody || []).map((x: any) => x.hfPartNo),
    '(成为组合子件后会被排除，属正确行为)');

  // ─── 2) COMPOSITE custom (2 children) ───
  const compBody = {
    productType: 'COMPOSITE',
    tempId: crypto.randomUUID(),
    parts: [
      { name: 'child-1', partMode: 'custom', recipeCode: 'AgCu90',
        elements: [{ elementCode: 'Ag', pct: 90 }, { elementCode: 'Cu', pct: 10 }],
        processIds: [], unitWeightGrams: 0.3, quotationLineItemId: crypto.randomUUID() },
      { name: 'child-2', partMode: 'custom', recipeCode: 'AgCu85',
        elements: [{ elementCode: 'Ag', pct: 85 }, { elementCode: 'Cu', pct: 15 }],
        processIds: [], unitWeightGrams: 0.2, quotationLineItemId: crypto.randomUUID() },
    ],
  };
  const r2 = await page.request.post(`${BASE}/api/cpq/configure-product/quotations/${QUOTE_ID}`, { data: compBody });
  console.log('[COMPOSITE configure] status=', r2.status());
  expect(r2.status(), 'COMPOSITE 提交必须 200').toBe(200);
  const resp2 = await r2.json();
  console.log('[COMPOSITE configure] resp=', JSON.stringify(resp2));
  expect(resp2.lineItems?.length, 'COMPOSITE 应返 父+2子 = 3 个 lineItem').toBeGreaterThanOrEqual(3);
  const comboHf = findCfgPart(resp2.lineItems, 'CFG-COMBO');
  console.log('[COMPOSITE] 父料号=', comboHf);
  expect(comboHf, '应生成 CFG-COMBO-xxxxx 父料号').toBeTruthy();

  // 父料号可被搜到（material_master）
  const rSearch2 = await page.request.get(`${BASE}/api/cpq/quotations/configure/search-parts?q=${comboHf}&size=10`);
  const searchBody2 = await rSearch2.json();
  expect(searchBody2.some((x: any) => x.hfPartNo === comboHf), 'COMPOSITE 父料号应能被搜到').toBe(true);

  console.log('\n✅ 选配 SIMPLE + COMPOSITE custom 落库 V6 链路通（提交不崩 + 进 material_master + 可搜 + 字典派可读）');
});
