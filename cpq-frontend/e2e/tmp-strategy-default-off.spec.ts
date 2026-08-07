/**
 * 需求 3 验证：客户价格调整策略「默认关闭」（PriceAdjustStrategyTab :79 / :247 → false）。
 *
 * 两项：
 *   ⑦ 从未配置过策略的客户 → 开关显示「关闭」（aria-checked=false）
 *   ⑧ 🔒 存量客户 CUST-0001（enabled=true）→ 开关仍显示「开启」，回显不得被误伤
 *
 * 🔒 全程**只看回显、绝不点保存**（后端正在对 CUST-0001 做存量变更）。
 *    本 spec 不点任何提交类按钮；写库与否由外层 SQL 用 row md5 前后对拍自证。
 *
 * 数据域：⑦ 用自建 `CUST-0805-FE`（新建、无策略）；⑧ 只读 CUST-0001。不碰 CUST-0729-*。
 * 建数：INSERT INTO customer (name, code) VALUES ('前端验证专用0805','CUST-0805-FE');
 * 跑法：PW_BASE_URL=http://localhost:5179 npx playwright test --config=e2e/playwright.config.ts \
 *        e2e/tmp-strategy-default-off.spec.ts --reporter=list
 * 用完即删。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const SHOT_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

let backendUp = false;
// 后端正在并发部署，一次性 3s 探测容易撞上重启窗口 → 整个 spec 被 skip（skipped 不是绿）。
test.beforeAll(async () => {
  for (let i = 0; i < 6 && !backendUp; i++) {
    backendUp = await isBackendUp();
    if (!backendUp) await new Promise((r) => setTimeout(r, 5000));
  }
  console.log(`[DF] backendUp=${backendUp}`);
});

/** 「启用状态」那个 Switch（按 Form.Item label 定位，避免撞到别的 Tab 面板里的开关） */
const enabledSwitch = (page: Page) =>
  page.locator('.ant-form-item').filter({ hasText: '启用状态' }).first().locator('button[role="switch"]');

async function openStrategyTab(page: Page, customerName: string) {
  await page.goto('/pricing');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  await page.locator('input[placeholder="搜索客户"]').first().fill(customerName);
  await page.locator('input[placeholder="搜索客户"]').first().press('Enter');
  await page.waitForTimeout(1800);
  await page.locator('.ant-list-item').filter({ hasText: customerName }).first().click();
  await page.waitForTimeout(1500);
  await page.locator('.ant-tabs-tab').filter({ hasText: '价格调整策略' }).first().click();
  await page.waitForTimeout(2500);
  await enabledSwitch(page).waitFor({ state: 'visible', timeout: 20_000 });
  // 等 load() 的回填落地（首帧是 initialValues，请求回来后才是真实状态）
  await page.waitForTimeout(1500);
}

/**
 * 读开关状态。
 * ⚠️ 量具坑：antd Switch 把「开启」「关闭」两个 inner span **同时**渲染在 DOM 里（靠位移显示其一），
 *   button.innerText 会拿到两个词拼起来的字符串，不能直接断言文案。以 aria-checked 为权威，
 *   另取当前可见的那个 inner span 作旁证。
 */
async function readSwitch(page: Page, tag: string) {
  const sw = enabledSwitch(page);
  const checked = await sw.getAttribute('aria-checked');
  const cls = (await sw.getAttribute('class')) ?? '';
  const visibleLabel = (await sw.locator(
    checked === 'true' ? '.ant-switch-inner-checked' : '.ant-switch-inner-unchecked',
  ).first().innerText().catch(() => '')).trim();
  console.log(`[DF][${tag}] aria-checked=${checked} 可见文案="${visibleLabel}" hasCheckedClass=${cls.includes('ant-switch-checked')}`);
  return { checked, visibleLabel };
}

test('⑦ 从未配置过策略的客户 → 开关默认「关闭」', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  test.skip(process.env.DF_CASE !== 'nostrat', '本轮不跑（需沙箱客户当前无策略）');
  await loginAsAdmin(page);
  await openStrategyTab(page, '前端验证专用0805');
  const s = await readSwitch(page, '沙箱/无策略');
  await page.screenshot({ path: path.join(SHOT_DIR, 'df-01-new-customer-off.png'), fullPage: true }).catch(() => {});
  expect(s.checked, '🔒 策略不存在时应默认关闭（改前为 true）').toBe('false');
  expect(s.visibleLabel, '文案应为「关闭」').toBe('关闭');
});

/**
 * ⑧ 值中性对照：**同一段代码、同一个客户，只改库里的 enabled**，看回显跟不跟着走。
 * 这是区分「回显读 DB」与「回显被写死成 false」的唯一办法 —— 只看到一次「关闭」证明不了任何事。
 * 期望值由外部 DF_SANDBOX_EXPECT 传入（跑两遍：DB=true 期望 true / DB=false 期望 false）。
 * 🔒 用自己的沙箱客户，不拿 CUST-0001 做对照（业务方刚对它做过存量变更 V380）。
 */
test('⑧ 值中性对照：沙箱客户已存在策略 → 回显跟随 DB 的 enabled', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  const expected = process.env.DF_SANDBOX_EXPECT;
  test.skip(expected !== 'true' && expected !== 'false', '本轮不跑（未指定 DF_SANDBOX_EXPECT）');
  await loginAsAdmin(page);
  await openStrategyTab(page, '前端验证专用0805');
  const s = await readSwitch(page, `沙箱/DB enabled=${expected}`);
  await page.screenshot({ path: path.join(SHOT_DIR, `df-02-sandbox-${expected}.png`), fullPage: true }).catch(() => {});
  expect(s.checked, `🔒 已存在策略必须回显 DB 真实值（本轮 DB=${expected}）`).toBe(expected);
  expect(s.visibleLabel).toBe(expected === 'true' ? '开启' : '关闭');
});

test('⑨ CUST-0001 只读复核：回显应等于其 DB 现值（不保存、不改任何字段）', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  const expected = process.env.DF_C1_EXPECT;
  test.skip(expected !== 'true' && expected !== 'false', '本轮不跑（未指定 DF_C1_EXPECT）');
  await loginAsAdmin(page);
  await openStrategyTab(page, '罗克韦尔');
  const s = await readSwitch(page, 'CUST-0001/存量');
  await page.screenshot({ path: path.join(SHOT_DIR, 'df-03-cust0001.png'), fullPage: true }).catch(() => {});
  expect(s.checked, `🔒 应与 DB 现值一致（V380 存量变更后为 ${expected}）`).toBe(expected);
  // 到此为止：本用例不点保存、不改任何字段
});
