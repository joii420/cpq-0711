/**
 * task-260902 · F-10「电镀方案」页签渲染取证（前端工程师自检用，**非正式测试用例**）
 * 🚨 同样是 `page.route` 打桩（8081 未部署 `/dataset/*`），**不构成联调证据**。
 * 响应包按真实结构 `{ code, message, data }`（无 success 字段）。
 */
import { test, expect } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';

const ok = (data: unknown) => ({ code: 200, message: 'success', data });

const QUOTE_COLUMNS = [
  ['scheme_no', '方案编号'], ['scheme_version', '版本'], ['item_seq', '项次'],
  ['plating_element', '电镀元素名称'], ['price_source_url', '元素单价来源网站网址'],
  ['price_source_name', '元素单价来源网站名称'], ['price_fetch_rule', '元素单价抓取规则'],
  ['plating_area', '电镀面积（cm2）'], ['coating_thickness', '镀层厚度（μm）'],
  ['plating_requirement', '电镀要求'],
].map(([name, label]) => ({ name, label, type: 'STRING' }));

const DETAIL_COLUMNS = [
  ['scheme_no', '方案编号'], ['scheme_version', '版本'], ['item_seq', '项次'],
  ['plating_element', '电镀元素名称'], ['plating_area', '电镀面积（cm2）'],
  ['coating_thickness', '镀层厚度（μm）'], ['plating_requirement', '电镀要求'],
  ['density', '密度（g/cm3)'],
].map(([name, label]) => ({ name, label, type: 'STRING' }));

let emptyMode = false;

test('AC-48/49/50/51 · 电镀方案页签', async ({ page }) => {
  test.setTimeout(180_000);
  const errs: string[] = [];
  page.on('pageerror', (e) => errs.push(String(e).slice(0, 200)));

  await loginAsAdmin(page);
  await page.route('**/api/cpq/dataset/**', async (route) => {
    const url = new URL(route.request().url());
    if (!url.pathname.endsWith('/plating-schemes')) {
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify(ok({ total: 0, items: [], sheets: [] })) });
    }
    const isQuote = url.pathname.includes('/dataset/quote/');
    if (emptyMode) {
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify(ok({ total: 0, columns: isQuote ? QUOTE_COLUMNS : DETAIL_COLUMNS, items: [] })) });
    }
    const items = isQuote ? [
      { scheme_no: 'A0001', scheme_version: '2000', item_seq: 1, plating_element: 'Ni',
        price_source_url: 'https://www.ccmn.cn/', price_source_name: '长江有色网', price_fetch_rule: '1.均价',
        plating_area: '0.031000000000', coating_thickness: '0.400000000000', plating_requirement: '镀层厚度≥0.4μm' },
      { scheme_no: 'A0001', scheme_version: '2000', item_seq: 2, plating_element: 'Au',
        price_source_url: 'https://www.ccmn.cn/', price_source_name: '长江有色网', price_fetch_rule: '2.最高价',
        plating_area: '0.037000000000', coating_thickness: '0.100000000000', plating_requirement: '镀层厚度≥0.1μm' },
    ] : [
      { scheme_no: 'A0001', scheme_version: '2000', item_seq: 1, plating_element: 'Ni',
        plating_area: '0.031000000000', coating_thickness: '0.400000000000',
        plating_requirement: '镀层厚度≥0.4μm', density: '8.902000000000' },
    ];
    return route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify(ok({ total: items.length, columns: isQuote ? QUOTE_COLUMNS : DETAIL_COLUMNS, items })) });
  });

  await page.goto('/master-data-hub');
  await page.waitForSelector('.ant-tabs-nav .ant-tabs-tab', { timeout: 60_000 });

  // AC-48：7 个页签，电镀方案在最后
  const tabs = (await page.locator('.ant-tabs-nav .ant-tabs-tab').allInnerTexts()).map((t) => t.trim());
  console.log('AC48_TAB_COUNT =', tabs.length);
  console.log('AC48_TABS =', JSON.stringify(tabs));

  await page.locator('.ant-tabs-nav .ant-tabs-tab', { hasText: '电镀方案' }).first().click();
  await page.waitForTimeout(2500);
  const pane = page.locator('.ant-tabs-tabpane-active').first();

  console.log('AC51_HINT =', JSON.stringify((await pane.locator('.ant-alert').innerText()).replace(/\s+/g, ' ').trim()));
  console.log('AC49_DATASET_DEFAULT =', JSON.stringify((await pane.locator('.ant-select').first().innerText()).replace(/\s+/g, ' ').trim()));

  const qh = (await pane.locator('.ant-table-thead th').allInnerTexts()).map((h) => h.trim());
  console.log('AC49_QUOTE_COL_COUNT =', qh.length);
  console.log('AC49_QUOTE_HEADERS =', JSON.stringify(qh));
  const row1 = (await pane.locator('.ant-table-tbody tr.ant-table-row').first().innerText()).replace(/\s+/g, ' ').trim();
  console.log('AC50_ROW1 =', JSON.stringify(row1));

  // AC-51：只读 —— 不存在写入口按钮，也没有可编辑单元格
  const btns = (await pane.locator('button').allInnerTexts()).map((b) => b.replace(/\s+/g, '')).filter(Boolean);
  console.log('AC51_BUTTONS =', JSON.stringify(btns));
  console.log('AC51_HAS_WRITE_BTN =', btns.some((b) => /新增|编辑|删除|保存/.test(b)));
  await pane.locator('.ant-table-tbody tr.ant-table-row td').first().click();
  await page.waitForTimeout(600);
  console.log('AC51_INPUTS_IN_TBODY =', await pane.locator('.ant-table-tbody tr.ant-table-row input').count());
  await page.screenshot({ path: 'e2e/screenshots/plating-01-quote.png', fullPage: true });

  // AC-49：切到「详细核价」→ 8 列，多「密度（g/cm3)」，少三列
  await pane.locator('.ant-select').first().click();
  await page.waitForTimeout(700);
  await page.locator('.ant-select-item-option', { hasText: '详细核价' }).first().click();
  await page.waitForTimeout(2500);
  const dh = (await pane.locator('.ant-table-thead th').allInnerTexts()).map((h) => h.trim());
  console.log('AC49_DETAIL_COL_COUNT =', dh.length);
  console.log('AC49_DETAIL_HEADERS =', JSON.stringify(dh));
  await page.screenshot({ path: 'e2e/screenshots/plating-02-detail.png', fullPage: true });

  // 空态
  emptyMode = true;
  await pane.locator('button', { hasText: '刷新' }).first().click();
  await page.waitForTimeout(2000);
  console.log('EMPTY_TEXT =', JSON.stringify((await pane.locator('.ant-table-placeholder').innerText()).replace(/\s+/g, ' ').trim()));
  await page.screenshot({ path: 'e2e/screenshots/plating-03-empty.png', fullPage: true });
  emptyMode = false;

  console.log('PAGE_ERRORS =', JSON.stringify(errs));
  expect(errs).toEqual([]);
});
