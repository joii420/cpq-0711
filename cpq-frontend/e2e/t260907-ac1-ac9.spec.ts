import { test, expect } from '@playwright/test';
import {
  BACKEND, TEMPLATE_ID, FG1, login, chintId, importFixture, createQuotation,
  gotoStep2, fg01Card, clickTab, waitBackendReady,
} from './t260907-helpers';

/**
 * T4.1 · AC-1（导入建单全链路） + T4.4 · AC-9（序列：改一格 → 保存 → 切走切回 → 刷新）
 *
 * 环境：worktree 临时栈（PW_BASE_URL=5198 / PW_BACKEND_URL=8099），🚫 不占主工作区 5174/8081。
 */

let session = '';
let customerId = '';
let created: any = null;

test.describe('T4.1 / T4.4 · AC-1 与 AC-9', () => {
  test.describe.configure({ mode: 'serial' });

  test.beforeAll(async ({ playwright }) => {
    const api = await playwright.request.newContext({ baseURL: BACKEND });
    // 🚩 beforeAll 钩子自带 30s 上限，装不下最长 120s 的就绪等待 —— 必须抬高，
    //    否则表现为「beforeAll hook timeout」，长得像夹具准备逻辑写错了。
    test.setTimeout(300_000);
    await waitBackendReady(api);
    session = await login(api);
    customerId = await chintId(api, session);
    const recId = await importFixture(api, session, customerId);
    created = await createQuotation(api, session, customerId, recId, 'T260907T-AC1-全链路');
    await api.dispose();
  });

  test.afterAll(async ({ playwright }) => {
    // 🚩 E2E 建的单不带料号前缀，JUnit 基座的前缀还原清不到它 —— spec 自己清
    if (!created?.quotationId) return;
    const api = await playwright.request.newContext({ baseURL: BACKEND });
    await api.delete(`/api/cpq/quotations/${created.quotationId}`, {
      headers: { Cookie: `CPQ_SESSION=${session}` },
    }).catch((e) => console.log('⚠️ 清理失败（单独报告，不顶替用例结论）:', String(e).slice(0, 150)));
    await api.dispose();
  });

  // ══════════════════ T4.1 · AC-1 ══════════════════

  test('T4.1 · AC-1：建单响应 / 库内行数 / 编辑页卡片数三者一致', async ({ page, playwright }) => {
    test.setTimeout(180_000);
    const api = await playwright.request.newContext({ baseURL: BACKEND });

    // ② 响应含非空 quotationId（quotationNumber 是 D-31 新加的）
    expect(created.quotationId, 'AC-1①：quotationId 必须非空').toBeTruthy();
    expect(created.quotationNumber, 'D-31：新建路径应带出 quotationNumber').toBeTruthy();

    // ③ quotation 客户正确
    const q = await api.get(`/api/cpq/quotations/${created.quotationId}`, {
      headers: { Cookie: `CPQ_SESSION=${session}` },
    });
    const qb = (await q.json()).data;
    expect(qb.customerId, 'AC-1②：新建单的 customer_id 应为正泰').toBe(customerId);
    expect(qb.customerTemplateId, '必须绑 ds 原生模板').toBe(TEMPLATE_ID);

    // ④ 明细行数 = 本次批次该客户的客户料号行数（D-26 批次口径）= 3
    //    ⚠️ 夹具物料表有 8 个料号，客户料号只有 3 行 —— 两个数不同，
    //       所以「明细行由客户料号决定、不由物料表决定」才验得出来。
    expect(created.lineItemsCount,
      `AC-1③：明细行数应 = 本次 Excel 客户料号 sheet 行数 = 3，实际 ${created.lineItemsCount}。`
      + ' ⚠️ 若明显偏大，先查 import_record.metadata.batchParts 有几条（D-26 批次口径），'
      + ' 再判是不是回归',
    ).toBe(3);

    const lines = await api.get(`/api/cpq/quotations/${created.quotationId}`, {
      headers: { Cookie: `CPQ_SESSION=${session}` },
    });
    const items = (await lines.json()).data?.lineItems ?? [];
    expect(items.length, 'AC-1③：库内明细行数应与响应一致').toBe(created.lineItemsCount);
    await api.dispose();

    // ⑤ 编辑页产品卡片数 = ④
    await page.context().addCookies([{
      name: 'CPQ_SESSION', value: session, domain: 'localhost', path: '/',
    }]);
    await gotoStep2(page, created.quotationId);
    const cards = page.locator('div.qt-product-card');
    const cardCount = await cards.count();
    expect(cardCount,
      `AC-1④：编辑页产品卡片数应 = 明细行数 ${created.lineItemsCount}，实际 ${cardCount}`,
    ).toBe(created.lineItemsCount);

    await page.screenshot({ path: 'test-results/t260907-ac1-step2.png', fullPage: true }).catch(() => {});
  });

  // ══════════════════ T4.4 · AC-9 ══════════════════

  /**
   * AC-9：改一格 → 保存 → **切走再切回** → **刷新页面**，三个时点值一致且为改后值。
   *
   * 🚩 两处刻意的选择（都是本任务的已知边界）：
   * 1. **不用「物料BOM」页签** —— 它不在本批 13 个里（D-34，等 F-3），用平铺的「来料固定加工费」。
   * 2. **不改「项次」列** —— `item_seq` 不参与 `row_fingerprint`（task-260902 AC-17 既定设计），
   *    改它保存必返 UNCHANGED，会验不到写路径还以为是缺陷。改「基准值」这个**对比项**列。
   */
  test('T4.4 · AC-9：改一格 → 保存 → 切走切回 → 刷新，三个时点一致', async ({ page }) => {
    test.setTimeout(240_000);
    const TAB = '来料固定加工费';
    const OTHER_TAB = '成品其他费用';
    const NEW_VALUE = '77.5';

    await page.context().addCookies([{
      name: 'CPQ_SESSION', value: session, domain: 'localhost', path: '/',
    }]);
    await gotoStep2(page, created.quotationId);

    let card = await fg01Card(page);
    await clickTab(card, page, TAB);

    // 定位「基准值」列的第一个可编辑输入框
    const headers = await card.locator('table thead th').allInnerTexts();
    const colIdx = headers.findIndex((h) => h.trim().includes('基准值'));
    expect(colIdx,
      `页签「${TAB}」里找不到「基准值」列，实际表头：${JSON.stringify(headers)}`,
    ).toBeGreaterThan(-1);

    const cell = card.locator('table tbody tr').first().locator('td').nth(colIdx);
    const input = cell.locator('input').first();
    await expect(input, `「基准值」单元格不可编辑（AC-9 需要一个可改的格）`).toHaveCount(1);

    const before = await input.inputValue();
    console.log(`改前值 = ${before} → 改为 ${NEW_VALUE}`);
    expect(before, 'AC-9 前置：被改的格原本就等于新值 ⇒ 改了等于没改，验不到写路径')
      .not.toBe(NEW_VALUE);

    await input.fill(NEW_VALUE);
    await input.blur();
    await page.waitForTimeout(1200);

    // ── 保存 ──（antd 会给两字按钮插空格）
    const save = page.locator('button').filter({ hasText: /保\s*存\s*草\s*稿/ }).first();
    await expect(save, '找不到「保存草稿」按钮').toHaveCount(1);
    // 🔬 T4_4_SKIP_SAVE=1 跳过保存。
    //    ⚠️ 这条**不能当证伪实验用**（2026-09-07 实测）：本页有 **blur 触发的 autosave**，
    //    不点保存值照样落库（实证：探针单 quote_values_at 比同单其它行晚 10s，
    //    且库里查得到那个值）。所以「跳过保存仍然绿」证明的是 autosave 存在，
    //    不是断言失效 —— **干预没落地 ≠ 用例无效**。
    //
    //    真正有效的证伪是**带外篡改权威存储**：
    //      UPDATE quotation_line_component_data SET row_data = replace(row_data::text,'旧','新')::jsonb
    //    然后刷新，DOM 必须显示「新」。实测通过（4242.42 → 555.55，刷新后 DOM = 555.55）
    //    ⇒ 时点3 确实在重读服务端状态，不是读客户端缓存。
    //    🚩 注意权威存储是 `quotation_line_component_data.row_data`，
    //       **不是** `quotation_line_item.quote_card_values`（那是派生副本，
    //       改它刷新后 DOM 不变，会让人误判成「读缓存」）。
    if (process.env.T4_4_SKIP_SAVE === '1') {
      console.log('🔬 跳过保存（注意：有 autosave，这不构成证伪）');
    } else {
      await save.click();
      await page.waitForTimeout(4000);
    }

    const readBack = async (label: string) => {
      const c = await fg01Card(page);
      await clickTab(c, page, TAB);
      const h = await c.locator('table thead th').allInnerTexts();
      const i = h.findIndex((x) => x.trim().includes('基准值'));
      const v = await c.locator('table tbody tr').first().locator('td').nth(i)
        .locator('input').first().inputValue();
      console.log(`时点「${label}」读回 = ${v}`);
      return v;
    };

    // 时点 1：保存后
    const t1 = await readBack('保存后');

    // 时点 2：切走再切回
    card = await fg01Card(page);
    await clickTab(card, page, OTHER_TAB);
    await page.waitForTimeout(800);
    const t2 = await readBack('切走再切回');

    // 时点 3：刷新页面
    await gotoStep2(page, created.quotationId);
    const t3 = await readBack('刷新页面后');

    await page.screenshot({ path: 'test-results/t260907-ac9-after-reload.png', fullPage: true })
      .catch(() => {});

    const fails: string[] = [];
    if (t1 !== NEW_VALUE) fails.push(`时点1（保存后）= ${t1}，期望 ${NEW_VALUE}`);
    if (t2 !== NEW_VALUE) fails.push(`时点2（切走再切回）= ${t2}，期望 ${NEW_VALUE}（切回丢值 = 状态没落地）`);
    if (t3 !== NEW_VALUE) fails.push(`时点3（刷新后）= ${t3}，期望 ${NEW_VALUE}（刷新丢值 = 没真正写库）`);
    expect(fails, `AC-9 不通过：\n  - ${fails.join('\n  - ')}`).toEqual([]);
  });
});
