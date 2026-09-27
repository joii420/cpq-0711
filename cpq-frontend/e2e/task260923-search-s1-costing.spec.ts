/**
 * E2E · task-260923 —— 测试分片 S-1（样本单 B → 核价单：核价工作台）
 *
 * 覆盖 AC-11。派生来源 / 选择器来源 / 分片纪律：见 task260923-search-s1.helpers.ts 文件头。
 *
 * 样本单 B（名称前缀 `T260923-S1B-`）：
 *   - 已存在「已提交且有核价单」的 B ⇒ 直接复用（不重复造，残留不滚雪球）；
 *   - 否则：接口建单 + PUT /draft 写 14 行 → 界面打开编辑页到 Step2（= 重新打开）→ POST /submit → 查 costing_order。
 *   - 🚫 B 与其核价单**不删**（删除已提交单据不在测试权限内），每轮把单号 / id / 核价单号写进 `90-残留清单.txt`。
 */
import { test, expect, Page } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';
import * as H from './task260923-search-s1.helpers';

let src: Record<string, H.SrcRow> = {};
let B = { id: '', no: '', coid: '', cono: '' };

function findB() {
  return H.sqlRows(`SELECT q.id::text, q.quotation_number, q.status, coalesce(co.id::text,''), coalesce(co.costing_order_number,'')
      FROM quotation q LEFT JOIN costing_order co ON co.quotation_id=q.id
     WHERE q.name LIKE '${H.PREFIX_B}%' ORDER BY q.created_at DESC, co.created_at DESC LIMIT 1`,
  ['id', 'no', 'status', 'coid', 'cono'])[0];
}

test.beforeAll(async ({ browser }) => {
  test.setTimeout(420_000);
  const others = H.otherPlaywright();
  expect(others, '有别的 playwright test 在跑 ⇒ 等它结束再跑').toEqual([]);
  await H.assertStackIdentity();
  src = H.loadSource();
  expect(H.expectedHits(src, '30003'), '样本自检：30003 应只命中 S0008~S0011').toEqual(H.SET_30003);

  let b = findB();
  if (!(b && b.status !== 'DRAFT' && b.coid)) {
    const cookie = await H.loginApi();
    let id = b?.status === 'DRAFT' ? b.id : '';
    if (!id) id = (await H.createSample(cookie, H.PREFIX_B, src, H.resolveTemplates())).id;
    else await H.verifySampleByApi(cookie, id, src);
    // 「保存、重新打开后提交」：界面打开编辑页进到 Step2，确认 14 行都在
    const page: Page = await browser.newPage({ baseURL: H.BASE_URL, viewport: { width: 1280, height: 800 } });
    await loginAsAdmin(page);
    await H.openEdit(page, id);
    expect(await H.countText(page), '样本单 B 重新打开后应为 共 14 条').toBe(H.TXT_ALL);
    await H.shot(page, 'T-11-样本单B重新打开');
    await page.close();
    const s = await H.api(cookie, 'POST', `/api/cpq/quotations/${id}/submit`);
    H.appendEvidence('01-造数.txt', `${new Date().toISOString()} 提交样本单 B ${id} → ${s.status} ${s.text.slice(0, 200)}`);
    expect(s.status, `样本单 B 提交失败 ${s.status} ${s.text.slice(0, 300)}`).toBe(200);
    for (let i = 0; i < 30 && !(b = findB())?.coid; i++) await new Promise((r) => setTimeout(r, 1000));
  }
  expect(b?.coid, '样本单 B 提交后应生成核价单').toMatch(/^[0-9a-f-]{36}$/);
  B = { id: b.id, no: b.no, coid: b.coid, cono: b.cono };
  H.appendEvidence('90-残留清单.txt',
    `${new Date().toISOString()} 样本单 B ${B.no} (${B.id}) 状态=${b.status}；核价单 ${B.cono} (${B.coid}) —— 不删，交主线处置`);

  // AC-11 非空前置：frozen_dto 里 14 个产品、S0008~S0011 的 hfPartInfo.partNo 非空（且 = 源值）
  const raw = H.sqlRO(`SELECT frozen_dto::text FROM costing_order WHERE id='${B.coid}'`);
  expect(raw, 'frozen_dto 应非空').toBeTruthy();
  const found: Record<string, string> = {};
  const walk = (o: any) => {
    if (!o || typeof o !== 'object') return;
    if (Array.isArray(o)) { o.forEach(walk); return; }
    if (typeof o.productPartNo === 'string' && 'hfPartInfo' in o) found[o.productPartNo] = o.hfPartInfo?.partNo ?? '';
    Object.values(o).forEach(walk);
  };
  walk(JSON.parse(raw));
  H.writeEvidence('T-11-frozen前置.json', JSON.stringify(found, null, 1));
  expect(Object.keys(found).length, `frozen_dto 里带 hfPartInfo 的产品数应为 14（实际 ${JSON.stringify(Object.keys(found))}）`).toBe(14);
  for (const s of H.SET_30003) {
    expect(found[s], `AC-11 前置：frozen_dto 中 ${s} 的 hfPartInfo.partNo 非空且 = 源 ${src[s].production}`).toBe(src[s].production);
  }
});

test('T-11 · AC-11 核价工作台：提示文字 / 不回车不变 / 回车生产料号命中 / ✕ 恢复', async ({ page }) => {
  test.setTimeout(240_000);
  await loginAsAdmin(page);
  await page.setViewportSize({ width: 1280, height: 800 });
  await H.openCostingReview(page, B.coid);
  const inp = H.searchInput(page);
  const rec: any = {};
  // ①
  rec.ph = await inp.getAttribute('placeholder');
  expect(rec.ph, 'AC-11①：提示文字逐字').toBe(H.PLACEHOLDER);
  // ②
  expect(await H.countText(page), 'AC-11 前置：共 14 条').toBe(H.TXT_ALL);
  await inp.click(); await inp.pressSequentially('30003', { delay: 80 }); await page.waitForTimeout(2000);
  expect(await inp.inputValue()).toBe('30003');
  rec.s2 = { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) };
  await H.shot(page, 'T-11-②未回车');
  expect(rec.s2.cnt, 'AC-11②：共 14 条').toBe(H.TXT_ALL);
  // ③
  await inp.press('Enter'); await page.waitForTimeout(800);
  rec.s3 = { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) };
  await H.shot(page, 'T-11-③回车');
  expect(rec.s3.cnt, 'AC-11③：计数').toBe(H.txtMatch(4));
  expect(H.sorted(rec.s3.nos), 'AC-11③：卡片').toEqual(H.SET_30003);
  // ④
  await H.bar(page).locator('.ant-input-clear-icon').first().click();
  rec.t4 = await H.within(1000, () => H.countText(page), H.TXT_ALL, 'AC-11④ 点 ✕ 后');
  await H.shot(page, 'T-11-④点叉后');
  H.writeEvidence('T-11.json', JSON.stringify(rec, null, 1));
});
