/**
 * repair-260918 · S-UI · T-UI-4 —— 涨跌率显示边界（page.route 注入 5 个取值 × 3 处页面 = 15 格）。
 *
 * AC-4（边界 · 显示）：涨跌率为 0.000000034611 时三处都显示 +<0.0001%（红）；为 -0.000000034611 时显示 -<0.0001%（绿）；
 *        为 0 时显示 0%（无颜色、无正负号）；为 -0.0125 时显示 -1.25%（绿）；为空时显示 —。
 *   三处 = 版本明细抽屉「涨跌」列 / 元素矩阵 V26091802 列银格涨跌小字 / 料号审核抽屉「一、为什么变」的「涨跌」列
 *   （AC-2 原文与 原型图/index.html 第 2 节「适用三处」）。
 *
 * 做法：真实接口先取回（route.fetch），只把**银 × V26091802** 那一处的 changeRate 换成注入值，其余字段原样；
 *       注入值保留原字段的 JSON 类型（字符串 / 数字字面量），见 helpers.serializeWithRate。
 * 「无颜色」口径 = 不设颜色、继承默认文字色（主线 2026-09-18 裁定）：涨跌文字 computed color 必须与参考色逐字相等 ——
 *   版本明细 / 审核抽屉：同行参考格（同一行第一个有文字的非目标格），灰、蓝等任何非继承色都判失败；
 *   元素矩阵：参考色由主线提供 = 现网原有中性灰 rgba(0, 0, 0, 0.35)，且非红非绿。
 * 阳性对照：同一选择器在 AC-1/AC-2 真实数据下读到 +0.0035%，而这里 5 个注入值各自读到不同文本 ⇒ 注入确实生效、读数确实来自该格。
 * 零业务写入：非 GET 的 /api 请求全部 abort 并断言为空。
 */
import { test, expect, type Page, type Route } from '@playwright/test';
import {
  AG, CUST, VERSION_NO, PLACEHOLDER,
  apiLogin, appendEvid, assertEnvIdentity, assertNoOtherPlaywright, assertRate, blockWrites, dump, elementMatrix, ensureDirs,
  fulfillText, openAdjustTab, openReviewDrawer, openVersionDetail, pickReview, probeRateCell, serializeWithRate, shot,
  unwrap, versionRow, type RateValue,
} from './repair260918-sui.helpers';

interface Case { value: RateValue; text: string; color: 'red' | 'green' | 'none' }
const CASES: Case[] = [
  { value: '0.000000034611', text: '+<0.0001%', color: 'red' },
  { value: '-0.000000034611', text: '-<0.0001%', color: 'green' },
  { value: '0', text: '0%', color: 'none' },
  { value: '-0.0125', text: '-1.25%', color: 'green' },
  { value: null, text: '—', color: 'none' },
];
const label = (c: Case) => (c.value === null ? 'null' : c.value);

/** 通用改写：取真实响应 → mutate 把目标字段置成 PLACEHOLDER 并返回原值 → 按原类型写回注入值。 */
async function rewrite(route: Route, value: RateValue, mutate: (body: any) => { orig: unknown } | null, log: string[]) {
  const resp = await route.fetch();
  const status = resp.status();
  const json = await resp.json().catch(() => null);
  const hit = json ? mutate(unwrap(json)) : null;
  if (!hit) {
    log.push(`MISS ${route.request().url()} status=${status}`);
    return route.fulfill({ response: resp });
  }
  const origType = hit.orig === null || hit.orig === undefined ? 'string' : typeof hit.orig;
  let body: string;
  try {
    body = serializeWithRate(json, value, origType);
  } catch (e) {
    // route handler 里抛错会让请求挂起、用例以超时收场 —— 改为记录 + 原样放行，由 finish() 的 HIT 断言硬失败
    log.push(`ERROR ${route.request().url()} ${String(e).slice(0, 200)}`);
    return route.fulfill({ response: resp });
  }
  log.push(`HIT ${route.request().url()} status=${status} orig=${JSON.stringify(hit.orig)}(${typeof hit.orig}) → ${value === null ? 'null' : value}(${origType})`);
  await fulfillText(route, status, body);
}

let versionId = '';
let review: ReturnType<typeof pickReview> | undefined; // 只在审核抽屉用例里按需取，避免前置缺失连带另两处失败

test.describe('S-UI · AC-4 涨跌率显示边界', () => {
  test.beforeAll(async ({ browser }) => {
    ensureDirs();
    assertNoOtherPlaywright();
    versionId = versionRow().id;
    const page = await browser.newPage();
    await assertEnvIdentity(page);
    await page.close();
  });

  async function finish(page: Page, place: string, c: Case, probe: any, log: string[], blocked: string[],
    ref: 'row' | 'matrixNeutral' = 'row') {
    appendEvid('AC-4-results.jsonl', { place, value: c.value, expectText: c.text, expectColor: c.color, probe, injectLog: log });
    console.log(`[AC-4][${place}][${label(c)}] injectLog=${JSON.stringify(log)}`);
    expect(log.some(l => l.startsWith('HIT')), `${place}：注入必须至少命中 1 次（0 次 = 页面没走这个接口或没找到银那一格，断言无效）`).toBe(true);
    assertRate(probe, c.text, c.color, `AC-4 ${place} ${label(c)}`, ref);
    expect(blocked, '零业务写入').toEqual([]);
  }

  for (const c of CASES) {
    test(`T-UI-4 [版本明细] changeRate=${label(c)} → ${c.text}（${c.color}）`, async ({ page }) => {
      const blocked = await blockWrites(page);
      const log: string[] = [];
      await page.route(new RegExp(`/api/cpq/price-adjust/versions/${versionId}/items`), async (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        await rewrite(route, c.value, (body) => {
          const arr: any[] = Array.isArray(body) ? body : body?.content;
          const ag = arr?.find((r: any) => r?.elementCode === AG.code);
          if (!ag) return null;
          const orig = ag.changeRate; ag.changeRate = PLACEHOLDER; return { orig };
        }, log);
      });
      await apiLogin(page);
      await openAdjustTab(page);
      const d = await openVersionDetail(page);
      const p = await probeRateCell(d, /^涨跌/, [AG.code, AG.name]);
      if (!p.found) await dump(page, `AC-4-version-${label(c)}`, '.ant-drawer:visible');
      await shot(d, `AC-4-版本明细-${label(c)}`);
      await finish(page, '版本明细', c, p, log, blocked, 'row');
    });
  }

  for (const c of CASES) {
    test(`T-UI-4 [元素矩阵] changeRate=${label(c)} → ${c.text}（${c.color}）`, async ({ page }) => {
      const blocked = await blockWrites(page);
      const log: string[] = [];
      await page.route(new RegExp(`/api/cpq/price-adjust/strategies/${CUST.no}/elements`), async (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        await rewrite(route, c.value, (body) => {
          const cols: any[] = body?.versionColumns;
          const idx = Array.isArray(cols) ? cols.findIndex((v: any) => v?.versionNo === VERSION_NO) : -1;
          const ag = (body?.content as any[] | undefined)?.find((r: any) => r?.elementCode === AG.code);
          if (idx < 0 || !ag || !Array.isArray(ag.prices) || !ag.prices[idx]) return null;
          const orig = ag.prices[idx].changeRate; ag.prices[idx].changeRate = PLACEHOLDER; return { orig };
        }, log);
      });
      await apiLogin(page);
      await openAdjustTab(page);
      const wrapper = await elementMatrix(page);
      const p = await probeRateCell(wrapper, new RegExp(VERSION_NO), [AG.code]);
      if (!p.found) await dump(page, `AC-4-matrix-${label(c)}`, '.ant-table-wrapper:visible');
      await wrapper.scrollIntoViewIfNeeded();
      await shot(wrapper, `AC-4-元素矩阵-${label(c)}`);
      // 元素矩阵：「无色」参考色由主线提供 —— 现网原有中性灰 rgba(0, 0, 0, 0.35)（master 上即如此），且非红非绿
      await finish(page, '元素矩阵', c, p, log, blocked, 'matrixNeutral');
    });
  }

  for (const c of CASES) {
    test(`T-UI-4 [审核抽屉] changeRate=${label(c)} → ${c.text}（${c.color}）`, async ({ page }) => {
      const r = (review ??= pickReview());
      const blocked = await blockWrites(page);
      const log: string[] = [];
      await page.route(new RegExp(`/api/cpq/price-adjust/reviews/${r.id}(\\?|$)`), async (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        await rewrite(route, c.value, (body) => {
          const ag = (body?.elementChanges as any[] | undefined)?.find((r: any) => r?.elementCode === AG.code);
          if (!ag) return null;
          const orig = ag.changeRate; ag.changeRate = PLACEHOLDER; return { orig };
        }, log);
      });
      await apiLogin(page);
      const d = await openReviewDrawer(page, r);
      const p = await probeRateCell(d, /^涨跌/, [AG.code, AG.name]);
      if (!p.found) await dump(page, `AC-4-review-${label(c)}`, '.ant-drawer:visible');
      await shot(d, `AC-4-审核抽屉-${r.material_no}-${label(c)}`);
      await finish(page, '审核抽屉', c, p, log, blocked, 'row');
    });
  }
});
