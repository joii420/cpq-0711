// task-260920 · 主线亲验（测试库夹具 T260920-43482dfe，临时栈 5295 → 8295 → cpq_db_test）
// 覆盖：AC-7 悬停/抽屉原因 · AC-25 两种驳回置灰提示 ·「有 n 项正在计算」· AC-11② 失败块在最上方 + approve 请求体不含失败行
//      · AC-8③「不会被更新的单」区块 · AC-27 已作废行不渲染/不轮询 · 切走标签页暂停轮询 · 抽屉打开即算并写回列表
// 「确认通过并升版」的请求被 route 截下记录后 abort —— 本脚本不做真升版。
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { execSync } from 'child_process';

const BASE = process.env.PW_BASE_URL!;
const EV = process.env.MV_EVIDENCE!;
const KW = 'T260920-43482dfe';
const USER = { username: 't260920-accept-43482dfe', password: 'T260920@Accept1' };
const ID = {
  A3: '8a621045-e266-43cf-88e1-6fee068ad532',
  B1: 'dfcc3d95-abc4-442d-a652-4f8e275d4cb6',
};
const obs: Record<string, unknown> = {};
const save = () => fs.writeFileSync(path.join(EV, 'obs.json'), JSON.stringify(obs, null, 2));
const shot = (page: Page, name: string) => page.screenshot({ path: path.join(EV, name), fullPage: false });

// 测试库夹具私有行的定点写（仅 A-0003 置 COMPUTING / 复原 QUEUED），库名写死 cpq_db_test
function sqlTestDb(q: string): string {
  return execSync(`PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_test -X -A -t -v ON_ERROR_STOP=1 -c "${q}"`, { encoding: 'utf8' }).trim();
}

const row = (page: Page, mat: string) => page.locator('.ant-table-tbody tr').filter({ hasText: `${KW}-${mat}` });
const btn = (page: Page, re: RegExp) => page.locator('button').filter({ hasText: re }).first();

async function query(page: Page) {
  await page.getByPlaceholder('搜索客户 / 料号 / 料号名称').fill(KW);
  await page.getByText('查询', { exact: true }).click();
  await expect(page.locator('.ant-table-tbody tr').filter({ hasText: KW }).first()).toBeVisible();
}
async function toggle(page: Page, mat: string) {
  await row(page, mat).locator('.ant-checkbox').first().click();
}
async function tooltipOf(page: Page, target: ReturnType<Page['locator']>) {
  await target.hover();
  const tip = page.locator('.ant-tooltip:visible').last();
  await expect(tip).toBeVisible();
  const t = (await tip.innerText()).trim();
  await page.mouse.move(5, 5);
  await expect(page.locator('.ant-tooltip:visible')).toHaveCount(0, { timeout: 5000 }).catch(() => undefined);
  return t;
}
async function selectStatus(page: Page, label: string) {
  const sel = page.locator('.ant-select').filter({ hasText: /待处理|已通过|已驳回|已作废/ }).first();
  await sel.click();
  await page.locator('[class*=dropdown]:visible').getByText(label, { exact: true }).click();
}

test('主线亲验 · 测试库夹具', async ({ page }) => {
  fs.mkdirSync(EV, { recursive: true });
  obs.startedAt = new Date().toISOString();
  const lr = await page.request.post(`${BASE}/api/cpq/auth/login`, { data: USER });
  obs.login = lr.status();
  expect(lr.ok()).toBeTruthy();

  const listReqs: { t: number; url: string }[] = [];
  const computeNow: { t: number; url: string }[] = [];
  page.on('request', (r) => {
    const u = r.url();
    if (r.method() === 'GET' && /\/api\/cpq\/price-adjust\/reviews\?/.test(u)) listReqs.push({ t: Date.now(), url: u });
    if (r.method() === 'POST' && /\/compute-now$/.test(u)) computeNow.push({ t: Date.now(), url: u });
  });

  await page.goto('/pricing/reviews');
  obs.urlAfterGoto = page.url();
  await query(page);

  await test.step('列表首屏：表头与接口计数一致', async () => {
    const api = await (await page.request.get(`${BASE}/api/cpq/price-adjust/reviews?page=1&size=20&keyword=${KW}`)).json();
    const d = api.data ?? api;
    obs.apiCounts = { totalElements: d.totalElements, notComputedTotal: d.notComputedTotal };
    obs.header = (await page.getByText(/共\s*\d+\s*条，其中/).first().innerText()).trim();
    obs.rowsInitial = await page.locator('.ant-table-tbody tr').filter({ hasText: KW }).allInnerTexts();
    await shot(page, '01-列表首屏-未计算态.png');
    save();
  });

  await test.step('AC-7 列表悬停看失败原因', async () => {
    obs.ac7Tooltip = await tooltipOf(page, row(page, 'C-0001').locator('.ant-tag').filter({ hasText: '预算失败' }));
    save();
  });

  await test.step('AC-7 抽屉显示原因、不自动重算', async () => {
    const before = computeNow.length;
    await row(page, 'C-0001').getByText(`${KW}-C-0001`, { exact: true }).click();
    const body = page.locator('.ant-drawer-body').last();
    await expect(body).toContainText('该料号的影响计算失败');
    await page.waitForTimeout(3000);
    obs.ac7Drawer = {
      failText: (await body.locator('.ant-alert').filter({ hasText: '计算失败' }).first().innerText()).trim(),
      hasRecomputeBtn: await body.locator('button').filter({ hasText: /重新\s*计算/ }).count(),
      computeNowFiredOnOpen: computeNow.length - before,
    };
    await shot(page, '02-AC7-抽屉-计算失败原因.png');
    await page.keyboard.press('Escape');
    await expect(page.locator('.ant-drawer-body:visible')).toHaveCount(0);
    save();
  });

  await test.step('AC-25 驳回置灰两种提示 + 通过仍可点', async () => {
    await toggle(page, 'A-0001');
    obs.ac25Queued = {
      hint: (await page.getByText(/其中\s*\d+\s*项未计算/).first().innerText()).trim(),
      rejectDisabled: await btn(page, /驳\s*回/).isDisabled(),
      rejectTip: await tooltipOf(page, btn(page, /驳\s*回/)),
      approveEnabled: await btn(page, /通过并升版/).isEnabled(),
    };
    await toggle(page, 'A-0001');
    await toggle(page, 'C-0001');
    obs.ac25Failed = {
      rejectDisabled: await btn(page, /驳\s*回/).isDisabled(),
      rejectTip: await tooltipOf(page, btn(page, /驳\s*回/)),
      approveEnabled: await btn(page, /通过并升版/).isEnabled(),
    };
    await shot(page, '03-AC25-失败行驳回置灰.png');
    await toggle(page, 'C-0001');
    save();
  });

  await test.step('「有 n 项正在计算，请稍候」（A-0003 临时置 COMPUTING，结束即复原）', async () => {
    obs.a3SetComputing = sqlTestDb(`UPDATE material_price_review SET budget_status='COMPUTING' WHERE id='${ID.A3}' AND status='PENDING' AND budget_status='QUEUED' RETURNING budget_status`);
    try {
      await query(page);
      await expect(row(page, 'A-0003')).toContainText('计算中');
      await toggle(page, 'A-0003');
      obs.computingHint = {
        cell: (await row(page, 'A-0003').innerText()).replace(/\s+/g, ' '),
        approveDisabled: await btn(page, /通过并升版/).isDisabled(),
        approveTip: await tooltipOf(page, btn(page, /通过并升版/)),
      };
      await shot(page, '04-正在计算-通过置灰.png');
      await toggle(page, 'A-0003');
    } finally {
      obs.a3Restore = sqlTestDb(`UPDATE material_price_review SET budget_status='QUEUED' WHERE id='${ID.A3}' AND status='PENDING' AND budget_status='COMPUTING' RETURNING budget_status`);
    }
    await query(page);
    save();
  });

  await test.step('AC-11② + AC-8③：批量通过（3 未计算含 1 失败 + 2 已计算）', async () => {
    let approveBody: any = null;
    await page.route('**/api/cpq/price-adjust/reviews/approve', async (route) => {
      approveBody = route.request().postDataJSON();
      await route.abort();
    });
    for (const m of ['A-0001', 'A-0002', 'B-0001', 'D-0001', 'D-0002']) await toggle(page, m);
    obs.ac8Hint = (await page.getByText(/其中\s*\d+\s*项未计算/).first().innerText()).trim();
    obs.ac8RejectTip = await tooltipOf(page, btn(page, /驳\s*回/));
    const cnBefore = computeNow.length;
    await btn(page, /通过并升版/).click();
    const modal = page.locator('.ant-modal:visible').last();
    await expect(modal).toBeVisible();
    const progressSeen: string[] = [];
    const t0 = Date.now();
    while (Date.now() - t0 < 120_000) {
      const txt = await modal.innerText().catch(() => '');
      const m = txt.match(/正在计算\s*\d+\s*\/\s*\d+/);
      if (m && !progressSeen.includes(m[0])) progressSeen.push(m[0]);
      if (/通过前影响面确认/.test(txt) && !/正在计算影响面/.test(txt)) break;
      await page.waitForTimeout(150);
    }
    await expect(modal).toContainText('版本推进路径');
    obs.ac11 = {
      progressSeen,
      computeNowCalls: computeNow.slice(cnBefore).map((c) => ({ dt: c.t - t0, url: c.url.replace(/^.*\/reviews\//, '') })),
      title: (await modal.locator('.ant-modal-title').innerText()).trim(),
      alertsInOrder: (await modal.locator('.ant-alert').allInnerTexts()).map((s) => s.replace(/\s+/g, ' ').slice(0, 160)),
      sections: {
        版本推进路径: await modal.getByText('版本推进路径').count(),
        按状态分组: await modal.getByText(/将更新的单（按状态分组）/).count(),
        各料号金额: await modal.getByText(/各料号金额/).count(),
        不会被更新的单: await modal.getByText(/张单不会被更新/).count(),
        跌破预警线: await modal.getByText(/跌破预警线/).count(),
      },
      modalText: (await modal.innerText()).slice(0, 3000),
    };
    await modal.screenshot({ path: path.join(EV, '05-AC11-确认框.png') });
    await modal.locator('button').filter({ hasText: /确认通过并升版/ }).click();
    const t1 = Date.now();
    while (!approveBody && Date.now() - t1 < 15_000) await page.waitForTimeout(100);
    obs.ac11ApproveBody = approveBody;
    obs.ac11ApproveExcludesFailed = !!approveBody && !(approveBody.reviewIds || []).includes(ID.B1);
    await page.unroute('**/api/cpq/price-adjust/reviews/approve');
    await page.waitForTimeout(1500);
    await shot(page, '06-AC11-确认后（请求已截下未发出）.png');
    const cancel = page.locator('.ant-modal:visible button').filter({ hasText: /取\s*消/ });
    if (await cancel.count()) await cancel.first().click();
    await page.keyboard.press('Escape');
    save();
  });

  await test.step('AC-27 已作废筛选：不显示未计算/计算、不轮询', async () => {
    await selectStatus(page, '已作废');
    await page.getByText('查询', { exact: true }).click();
    await expect(row(page, 'F-0001')).toBeVisible();
    const n0 = listReqs.length;
    await page.waitForTimeout(35_000);
    obs.ac27 = {
      f1: (await row(page, 'F-0001').innerText()).replace(/\s+/g, ' '),
      f2: (await row(page, 'F-0002').innerText()).replace(/\s+/g, ' '),
      computeLinks: await page.locator('.ant-table-tbody tr').filter({ hasText: `${KW}-F-` }).getByText('计算', { exact: true }).count(),
      notComputedTags: await page.locator('.ant-table-tbody tr').filter({ hasText: `${KW}-F-` }).getByText('未计算', { exact: true }).count(),
      header: await page.getByText(/共\s*\d+\s*条/).first().innerText().catch(() => ''),
      listRequestsIn35s: listReqs.length - n0,
    };
    await shot(page, '07-AC27-已作废筛选.png');
    save();
  });

  await test.step('切走标签页暂停轮询（待处理筛选、有未计算行）', async () => {
    await selectStatus(page, '待处理');
    await page.getByText('查询', { exact: true }).click();
    await expect(row(page, 'A-0003')).toContainText('未计算');
    const v0 = listReqs.length;
    await page.waitForTimeout(35_000);
    const visible35 = listReqs.length - v0;
    await page.evaluate(() => {
      Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
      Object.defineProperty(document, 'hidden', { value: true, configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    const h0 = listReqs.length;
    await page.waitForTimeout(35_000);
    const hidden35 = listReqs.length - h0;
    await page.evaluate(() => {
      Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
      Object.defineProperty(document, 'hidden', { value: false, configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    const r0 = listReqs.length;
    await page.waitForTimeout(8_000);
    obs.pollPause = { visible35, hidden35, afterVisible8s: listReqs.length - r0 };
    save();
  });

  await test.step('抽屉打开即算（A-0003 未计算）→ 关闭后列表已写回', async () => {
    const before = computeNow.length;
    const tOpen = Date.now();
    await row(page, 'A-0003').getByText(`${KW}-A-0003`, { exact: true }).click();
    const body = page.locator('.ant-drawer-body').last();
    await expect(body).toBeVisible();
    let doneAt = 0;
    while (Date.now() - tOpen < 60_000) {
      const txt = await body.innerText().catch(() => '');
      if (!/计算中|正在计算/.test(txt) && /比对|报价/.test(txt) && computeNow.length > before) { doneAt = Date.now(); break; }
      await page.waitForTimeout(200);
    }
    obs.drawerAutoCompute = {
      computeNowCalls: computeNow.length - before,
      msToDone: doneAt ? doneAt - tOpen : null,
      bodyText: (await body.innerText()).replace(/\s+/g, ' ').slice(0, 800),
    };
    await shot(page, '08-抽屉打开即算-完成.png');
    await page.keyboard.press('Escape');
    await page.waitForTimeout(1500);
    obs.drawerAfterCloseRow = (await row(page, 'A-0003').innerText()).replace(/\s+/g, ' ');
    await shot(page, '09-关闭抽屉后列表写回.png');
    save();
  });

  obs.finishedAt = new Date().toISOString();
  save();
});
