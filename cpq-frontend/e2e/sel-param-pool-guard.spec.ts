/**
 * 选配参数池守卫 spec —— 防止 sel_param_type 种子再次丢失 / 空态文案退化。
 *   V1 真实数据下「新建模板」能开抽屉并列出三类参数（种子恢复 + 主路径未被改坏）
 *   V2 接口返回空数组 → 弹「参数池为空…」而不是「加载中」（改动真的生效）
 *   V3 接口悬挂未返回 → 仍弹「参数池加载中」（旧行为保留，没被误删）
 * V2/V3 用 page.route 拦截造，不触碰数据库。
 *
 * ⚠️ 选择器坑：本项目 antd 抽屉根节点不带 .ant-drawer-content 类。用它做
 *    toHaveCount(0) 会因"永不存在"而恒真 —— 是空验证。一律用 role=dialog。
 */
import { test, expect } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';

const PAGE = '/config/sel-templates';
const API = '**/api/cpq/sel-param-types';
const drawerOf = (page: any) => page.getByRole('dialog', { name: '新建选配模板' });

test('V1 真实数据：新建模板可打开，列出三类参数', async ({ page }) => {
  await loginAsAdmin(page);
  await page.goto(PAGE);
  await expect(page.getByText('选配模板管理')).toBeVisible({ timeout: 15_000 });

  // 参数池请求可能还在飞（那会命中 loading 分支并 early return），点击带重试直到抽屉真开
  await expect(async () => {
    await page.getByRole('button', { name: /新\s*建/ }).first().click();
    await expect(drawerOf(page)).toBeVisible({ timeout: 3_000 });
  }).toPass({ timeout: 20_000 });

  const drawer = drawerOf(page);
  await expect(drawer).toContainText('材质');
  await expect(drawer).toContainText('元素含量');
  await expect(drawer).toContainText('工序');
  await page.screenshot({ path: 'test-results/sel-param-pool/v1-drawer.png', fullPage: true });
});

test('V2 接口返回空数组：提示「参数池为空」而非「加载中」', async ({ page }) => {
  await loginAsAdmin(page);
  await page.route(API, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: [] }) }));
  await page.goto(PAGE);
  await expect(page.getByText('选配模板管理')).toBeVisible({ timeout: 15_000 });
  await page.getByRole('button', { name: /新\s*建/ }).first().click();

  await expect(page.locator('.ant-message')).toContainText('参数池为空', { timeout: 10_000 });
  await expect(page.locator('.ant-message')).not.toContainText('请稍候再试');
  await expect(drawerOf(page)).toHaveCount(0);
  await page.screenshot({ path: 'test-results/sel-param-pool/v2-empty.png', fullPage: true });
});

test('V3 接口未返回：提示仍是「参数池加载中」', async ({ page }) => {
  await loginAsAdmin(page);
  // 永不 fulfill，模拟请求悬挂 => paramTypesLoading 恒 true
  await page.route(API, async () => { await new Promise(() => {}); });
  await page.goto(PAGE);
  await expect(page.getByText('选配模板管理')).toBeVisible({ timeout: 15_000 });
  await page.getByRole('button', { name: /新\s*建/ }).first().click();

  await expect(page.locator('.ant-message')).toContainText('参数池加载中，请稍候再试', { timeout: 10_000 });
  await expect(drawerOf(page)).toHaveCount(0);
});
