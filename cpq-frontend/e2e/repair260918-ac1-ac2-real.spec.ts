/**
 * repair-260918 · S-UI · T-UI-1 / T-UI-2 —— 开发库真实数据（只读），涨跌率小幅变动的显示。
 *
 * AC-1（单点 · 阴性）：开发环境，定价管理 → 价格调整策略 → 版本轨迹，点 V26091802 打开「版本明细」
 *        → 银那一行「涨跌」列显示 +0.0035%，红色（#cf1322）；不再显示 +0%。
 * AC-2（单点）：同一数值在另外两处显示一致：① 该客户「元素矩阵」中 V26091802 列银格的涨跌小字显示 +0.0035%；
 *        ② 打开 V26091802 任一待处理料号的审核抽屉，「一、为什么变」表格银行「涨跌」列显示 +0.0035%。
 *
 * 前置（只读 SQL 核）：CUST-0004 正泰 · V26091802 · 银 本期 28893.5 / 上期 28892.5 / change_rate 0.000035。
 * 零业务写入：非 GET 的 /api 请求全部 abort 并断言为空；审核列表只做「过滤到目标行」的改写，不改字段值。
 */
import { test, expect } from '@playwright/test';
import {
  AG, CUST, VERSION_NO, RED,
  apiLogin, assertEnvIdentity, assertNoOtherPlaywright, assertRate, blockWrites, dump, elementMatrix, ensureDirs,
  EVID_DIR, info, openAdjustTab, openReviewDrawer, openVersionDetail, pickReview, probeRateCell, shot, versionRow, writeEvid,
} from './repair260918-sui.helpers';
import * as fs from 'fs';
import * as path from 'path';

const EXPECT = '+0.0035%';
// 各用例结果落盘（失败后 worker 会重启，模块级变量会丢）；汇总用例从文件读
const RES = (k: string) => `AC-1-AC-2-${k}.json`;
function readRes(k: string): any {
  const p = path.join(EVID_DIR, RES(k));
  return fs.existsSync(p) ? JSON.parse(fs.readFileSync(p, 'utf8')) : null;
}

// 不用 serial：一条失败不应让其余两处跳过（三处各自是独立的可观测断言）
test.describe('S-UI · AC-1 / AC-2 真实数据', () => {
  test.beforeAll(async ({ browser }) => {
    ensureDirs();
    assertNoOtherPlaywright();
    versionRow(); // 前置数据核对（不符 ⇒ 前置漂移，不是产品缺陷）
    const page = await browser.newPage();
    await assertEnvIdentity(page);
    await page.close();
  });

  test('T-UI-1 · AC-1 版本明细：银「涨跌」= +0.0035% 红；序列：关抽屉→重开→刷新页面后仍一致', async ({ page }, ti) => {
    const blocked = await blockWrites(page);
    await apiLogin(page);
    await openAdjustTab(page);
    let d = await openVersionDetail(page);

    let p = await probeRateCell(d, /^涨跌/, [AG.code, AG.name]);
    if (!p.found) await dump(page, 'AC-1-version-detail', '.ant-drawer:visible');
    const r1: any = { first: p };
    writeEvid(RES('AC-1'), r1); // 先落盘再断言：失败时也留下实际值
    await shot(d, 'AC-1-版本明细-V26091802-银');
    const hit = assertRate(p, EXPECT, 'red', 'AC-1 版本明细·首次');
    expect(hit.text, 'AC-1 阴性：不得再显示 +0%').not.toBe('+0%');

    // 序列：关掉抽屉再重开（重开可能先显示上一次内容 —— 此处上一次内容与期望相同，故额外做一次整页刷新）
    await page.keyboard.press('Escape');
    if (!(await d.isHidden({ timeout: 3000 }).catch(() => false))) await d.locator('.ant-drawer-close, .ant-modal-close').first().click();
    await expect(d).toBeHidden({ timeout: 10_000 });
    d = await openVersionDetail(page);
    p = await probeRateCell(d, /^涨跌/, [AG.code, AG.name]);
    assertRate(p, EXPECT, 'red', 'AC-1 版本明细·重开');

    await page.reload();
    await page.waitForLoadState('networkidle').catch(() => {});
    await openAdjustTab(page);
    d = await openVersionDetail(page);
    p = await probeRateCell(d, /^涨跌/, [AG.code, AG.name]);
    assertRate(p, EXPECT, 'red', 'AC-1 版本明细·刷新后');
    await shot(d, 'AC-1-版本明细-刷新后');
    r1.afterReload = p;
    writeEvid(RES('AC-1'), r1);

    info(ti, 'AC-1 实际', `${hit.text} ${hit.color}（期望 ${EXPECT} ${RED}）`);
    expect(blocked, '零业务写入：本用例不得发出任何非 GET 请求').toEqual([]);
  });

  test('T-UI-2① · AC-2 元素矩阵：V26091802 列银格涨跌小字 = +0.0035% 红', async ({ page }, ti) => {
    const blocked = await blockWrites(page);
    await apiLogin(page);
    await openAdjustTab(page);
    const wrapper = await elementMatrix(page);
    // 版本列表头含版本号（可能带日期 / 待审角标）⇒ 用「含」匹配；行按元素符号 Ag 精确 token 匹配（避开 AgNi11 等）
    const p = await probeRateCell(wrapper, new RegExp(VERSION_NO), [AG.code]);
    if (!p.found) await dump(page, 'AC-2-element-matrix', '.ant-table-wrapper:visible');
    writeEvid(RES('AC-2-1'), p);
    await wrapper.scrollIntoViewIfNeeded();
    await shot(wrapper, 'AC-2-1-元素矩阵-V26091802-银');
    expect(p.cellText, 'AC-2①：该格应同时有单价 28893.5（证明取的是 V26091802 列而不是别的版本列）').toMatch(/28,?893\.5/);
    const hit = assertRate(p, EXPECT, 'red', 'AC-2① 元素矩阵');
    info(ti, 'AC-2① 实际', `${hit.text} ${hit.color}；格文本「${p.cellText}」`);
    expect(blocked, '零业务写入').toEqual([]);
  });

  test('T-UI-2② · AC-2 审核抽屉「一、为什么变」：银「涨跌」= +0.0035% 红', async ({ page }, ti) => {
    const blocked = await blockWrites(page);
    await apiLogin(page);
    const review = pickReview();
    info(ti, 'AC-2② 选用审核', review);
    const d = await openReviewDrawer(page, review);
    const p = await probeRateCell(d, /^涨跌/, [AG.code, AG.name]);
    if (!p.found) await dump(page, 'AC-2-review-drawer', '.ant-drawer:visible');
    writeEvid(RES('AC-2-2'), { review, probe: p });
    await shot(d, `AC-2-2-审核抽屉-${review.material_no}-银`);
    const hit = assertRate(p, EXPECT, 'red', 'AC-2② 审核抽屉');
    info(ti, 'AC-2② 实际', `${hit.text} ${hit.color}`);
    expect(blocked, '零业务写入').toEqual([]);
  });

  test('T-UI-2 · 三处一致（汇总）', async () => {
    const texts = [readRes('AC-1')?.first, readRes('AC-2-1'), readRes('AC-2-2')?.probe]
      .map((probe: any) => probe?.rateHits?.[0]?.text);
    console.log(`[AC-2 一致性] ${JSON.stringify(texts)} customer=${CUST.no}`);
    expect(texts.every(t => typeof t === 'string'), '三处都必须实际读到值（任一缺失 = 前面用例没跑到，不是一致）').toBe(true);
    expect(new Set(texts).size, `三处显示必须一致：${JSON.stringify(texts)}`).toBe(1);
    expect(texts[0]).toBe(EXPECT);
  });
});
